package com.clauditor.toolwindow

import com.clauditor.util.ClaudePathEncoder
import com.clauditor.util.ClauditorExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.awt.event.HierarchyEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/**
 * Caches per-worktree git status for the Worktrees session list, so the table can show
 * uncommitted/unmerged state without every row shelling out to git on the EDT.
 *
 * Keyed by worktree *name*, not session id — several sessions can share one worktree, and
 * they'd otherwise each pay for the same sweep.
 *
 * ## Polling discipline
 *
 * A sweep costs ~2-4 short git invocations per worktree (~30-50 ms each), so the expensive
 * mistake is running it often, not running it at all. Every request passes four gates:
 *
 *  1. **Visibility** — nothing runs unless the panel is actually on screen. A hidden tool
 *     window or a non-selected tab means zero git processes.
 *  2. **Throttle** — event-driven requests coalesce to at most one sweep per
 *     [MIN_SWEEP_INTERVAL_MS]. This matters because the session list reloads on every
 *     transcript write, which is continuous while Claude is working.
 *  3. **Single-flight** — a request arriving mid-sweep is dropped, never queued.
 *  4. **Empty short-circuit** — no worktree rows means no git at all.
 *
 * The periodic tick is a backstop for changes we have no event for (the user committing in
 * a terminal), not the primary refresh path; becoming visible triggers an immediate sweep.
 */
class WorktreeStatusCache(
    private val project: Project,
    parent: Disposable,
    private val onUpdated: () -> Unit
) : Disposable {

    private companion object {
        /** Floor between event-driven sweeps. Explicit user refreshes bypass it. */
        const val MIN_SWEEP_INTERVAL_MS = 15_000L
        /** Backstop tick for out-of-band changes (commits made in a terminal). */
        const val PERIODIC_MS = 30_000L
    }

    private val cache = ConcurrentHashMap<String, WorktreeStatus>()
    private val inFlight = AtomicBoolean(false)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile private var targets: List<String> = emptyList()
    @Volatile private var visible = false
    @Volatile private var lastSweepAt = 0L

    init {
        Disposer.register(parent, this)
        alarm.addRequest(::tick, PERIODIC_MS)
    }

    /** Latest known status for [worktreeName], or null if it hasn't been computed yet. */
    fun get(worktreeName: String): WorktreeStatus? = cache[worktreeName]

    /** The worktrees currently on screen. Sweeps only ever touch these. */
    fun setTargets(names: Collection<String>) {
        targets = names.distinct()
    }

    /**
     * Track [component]'s on-screen state, so sweeps stop while the panel is hidden and
     * resume (immediately, bypassing the throttle) when it comes back.
     */
    fun trackVisibility(component: JComponent) {
        visible = component.isShowing
        component.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) return@addHierarchyListener
            val nowVisible = component.isShowing
            val became = nowVisible && !visible
            visible = nowVisible
            // Coming back into view: the cache is as stale as the time spent hidden.
            if (became) requestRefresh(force = true)
        }
    }

    /**
     * Ask for a sweep. Honours the gates above unless [force] (the toolbar's Refresh
     * button), which skips the throttle but never the single-flight guard.
     */
    fun requestRefresh(force: Boolean = false) {
        if (!visible) return
        val names = targets
        if (names.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSweepAt < MIN_SWEEP_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastSweepAt = now

        ClauditorExecutor.submit {
            try {
                sweep(names)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** Sequential on purpose: N worktrees must not mean N git processes at once. */
    private fun sweep(names: List<String>) {
        val basePath = project.basePath ?: return
        var changed = false
        for (name in names) {
            val path = ClaudePathEncoder.worktreeAbsolutePath(basePath, name)
            // status() short-circuits on a missing directory without spawning git.
            val status = WorktreeInspector.status(path, basePath)
            if (cache.put(name, status) != status) changed = true
        }
        if (changed) ApplicationManager.getApplication().invokeLater(onUpdated)
    }

    private fun tick() {
        requestRefresh()
        if (!alarm.isDisposed) alarm.addRequest(::tick, PERIODIC_MS)
    }

    override fun dispose() {
        cache.clear()
    }
}
