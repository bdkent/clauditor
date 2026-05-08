package com.clauditor.util

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded executor owned by Clauditor. All background work that could block on
 * external state (git invocations, file scans, status polls) MUST run here, not
 * on Application.executeOnPooledThread, so we cannot starve the IDE-shared pool.
 *
 * The cap (settings.backgroundPoolMaxThreads, default 16) is sized to cover peak
 * per-tab refresh work — each open session tab can have up to 2 in-flight refreshes
 * (git toolbar + worktree status), gated by single-flight. A typical user with ~8
 * session tabs across IDE windows will burst to ~16. If the pool runs hot, a warning
 * is logged at submit time so the cap can be tuned upward in settings.
 *
 * Threads are daemon and named "Clauditor-N" so they show up clearly in thread dumps.
 * Idle threads expire after 60 s; at idle the pool holds zero threads.
 */
object ClauditorExecutor {

    private val log = Logger.getInstance(ClauditorExecutor::class.java)
    private val counter = AtomicLong(0)
    private const val DEFAULT_MAX = 16

    private val factory = ThreadFactory { r ->
        Thread(r, "Clauditor-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                log.warn("Uncaught in ${t.name}", e)
            }
        }
    }

    private val executor = ThreadPoolExecutor(
        DEFAULT_MAX, DEFAULT_MAX,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        factory
    ).apply { allowCoreThreadTimeOut(true) }

    @Volatile private var lastConfiguredMax = DEFAULT_MAX
    @Volatile private var lastSaturationWarnNanos = 0L
    private val resizeLock = Any()

    private fun configuredMax(): Int = try {
        com.clauditor.settings.ClauditorSettings.getInstance()
            .state.backgroundPoolMaxThreads
            .coerceIn(2, 64)
    } catch (_: Throwable) {
        DEFAULT_MAX
    }

    private fun ensurePoolSize(target: Int) {
        if (target == lastConfiguredMax) return
        synchronized(resizeLock) {
            if (target == lastConfiguredMax) return
            // When growing, set core first; when shrinking, set max first.
            // Otherwise core>max or max<core can transiently violate the invariant.
            if (target > lastConfiguredMax) {
                executor.corePoolSize = target
                executor.maximumPoolSize = target
            } else {
                executor.maximumPoolSize = target
                executor.corePoolSize = target
            }
            log.info("Clauditor: background pool resized $lastConfiguredMax -> $target")
            lastConfiguredMax = target
        }
    }

    fun submit(task: Runnable) {
        val target = configuredMax()
        ensurePoolSize(target)

        val active = executor.activeCount
        if (active >= target) {
            // Throttle the warning to once every 60 s so a saturated burst doesn't
            // spam the log with one line per submission.
            val now = System.nanoTime()
            if (now - lastSaturationWarnNanos > 60_000_000_000L) {
                lastSaturationWarnNanos = now
                log.warn(
                    "Clauditor: background pool saturated — $active/$target threads active, " +
                        "${executor.queue.size} queued. Raise 'Background pool max threads' " +
                        "in Settings > Tools > Clauditor if this persists."
                )
            }
        }

        executor.execute(task)
    }
}
