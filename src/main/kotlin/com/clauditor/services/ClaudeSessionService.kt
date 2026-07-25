package com.clauditor.services

import com.clauditor.model.SessionDisplay
import com.clauditor.util.ClaudePathEncoder
import com.clauditor.util.ClaudeProcessDetector
import com.clauditor.util.ClauditorExecutor
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ClaudeSessionService(private val project: Project) : Disposable {

    private val gson = Gson()
    private val log = Logger.getInstance(ClaudeSessionService::class.java)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var cachedSessions: List<SessionDisplay>? = null

    /**
     * Per-transcript incremental parse state, keyed by file.
     *
     * The session list is rebuilt on every transcript write, and transcripts reach tens of
     * megabytes — re-parsing every file from the top each time made the cost of watching a
     * session grow with its own history. Every field the list needs accumulates cleanly over
     * appended lines (counts add, the first prompt sticks, the newest title and timestamp
     * win), so each pass only parses the bytes that are new.
     */
    private val accumulators = java.util.concurrent.ConcurrentHashMap<Path, SessionAccumulator>()

    private class SessionAccumulator {
        val tail = com.clauditor.util.JsonlTailReader()
        var firstPrompt: String? = null
        var customTitle: String? = null
        var messageCount = 0
        var gitBranch: String? = null
        var lastTimestamp: Instant = Instant.EPOCH

        /** Drop everything: the file was rewritten, so accumulated totals no longer apply. */
        fun reset() {
            firstPrompt = null
            customTitle = null
            messageCount = 0
            gitBranch = null
            lastTimestamp = Instant.EPOCH
        }
    }

    @Volatile
    private var lastKnownMtime: Long = 0

    /** Session IDs running in external terminals (not Clauditor). */
    @Volatile
    private var externalSessionIds: Set<String> = emptySet()

    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Last external-session sweep; that sweep shells out to `ps`, so it gets a floor. */
    @Volatile
    private var lastExternalSweep = 0L

    fun isExternallyOpen(sessionId: String): Boolean =
        sessionId in externalSessionIds

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun getSessions(): List<SessionDisplay> {
        var result = cachedSessions
        if (result == null) {
            result = loadSessions()
            cachedSessions = result
        }
        return result
    }

    fun deleteSession(sessionId: String): Boolean {
        val basePath = project.basePath ?: return false

        // Find the session to determine if it's from a worktree
        val session = cachedSessions?.find { it.sessionId == sessionId }
        val projectDir = if (session?.worktreeName != null) {
            ClaudePathEncoder.worktreeProjectDir(basePath, session.worktreeName)
        } else {
            ClaudePathEncoder.projectDir(basePath)
        }

        val jsonlPath = projectDir.resolve("$sessionId.jsonl")

        try {
            Files.deleteIfExists(jsonlPath)
        } catch (e: Exception) {
            log.warn("Failed to delete session file: $jsonlPath", e)
            return false
        }

        // Remove session subdirectory (tool-results, subagents, etc.)
        val sessionDir = projectDir.resolve(sessionId)
        try {
            if (Files.isDirectory(sessionDir)) {
                Files.walk(sessionDir).use { paths ->
                    for (p in paths.sorted(Comparator.reverseOrder())) {
                        Files.deleteIfExists(p)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to delete session directory: $sessionDir", e)
        }

        // Update cache directly — avoids race with concurrent background refresh overwriting
        // the corrected cache before listeners fire
        cachedSessions = cachedSessions?.filter { it.sessionId != sessionId }
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it() }
        }
        return true
    }

    /**
     * Rebuild the session list and notify listeners.
     *
     * Always runs on a background thread: the VFS listener that drives most refreshes fires
     * in a write action on the EDT, so doing the file scan (and the external-session process
     * sweep) inline blocked the UI thread on every transcript write.
     *
     * Overlapping requests coalesce — a call arriving mid-pass sets a flag that schedules
     * exactly one more pass afterwards, rather than queueing a pass per event.
     */
    fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            refreshQueued.set(true)
            return
        }
        ClauditorExecutor.submit {
            try {
                cachedSessions = loadSessions()
                refreshExternalSessions()
                ApplicationManager.getApplication().invokeLater {
                    listeners.forEach { it() }
                }
            } finally {
                refreshInFlight.set(false)
                // State changed while we were working: run once more, not once per event.
                if (refreshQueued.compareAndSet(true, false)) refresh()
            }
        }
    }

    fun startWatching() {
        val basePath = project.basePath ?: return
        val projectDirs = ClaudePathEncoder.projectDirCandidates(basePath)

        project.messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val projectDirStrs = projectDirs.map { it.toString() }
                    val sessionsDirStr = ClaudePathEncoder.sessionsDir().toString()
                    val relevant = events.any { event ->
                        val path = event.path
                        (projectDirStrs.any { path.startsWith(it) } && path.endsWith(".jsonl")) ||
                            ((event is VFileContentChangeEvent || event is VFileCreateEvent) &&
                                path.startsWith(sessionsDirStr) &&
                                path.endsWith(".json"))
                    }
                    if (relevant) {
                        refresh()
                    }
                }
            })

        val vfm = VirtualFileManager.getInstance()
        for (dir in projectDirs) vfm.refreshAndFindFileByNioPath(dir)
        vfm.refreshAndFindFileByNioPath(ClaudePathEncoder.sessionsDir())

        startPolling()
    }

    private fun startPolling() {
        // First check runs quickly so external session status is known before the first render
        pollAlarm.addRequest(::checkForChanges, 200)
    }

    private fun checkForChanges() {
        val basePath = project.basePath ?: return

        // Check main project dir(s) and all worktree project dirs
        val dirsToCheck = ClaudePathEncoder.projectDirCandidates(basePath).toMutableList()
        try {
            for (name in ClaudePathEncoder.worktreeNames(basePath)) {
                dirsToCheck.add(ClaudePathEncoder.worktreeProjectDir(basePath, name))
            }
        } catch (_: Exception) {}

        try {
            var latestMtime = 0L
            for (dir in dirsToCheck) {
                if (Files.isDirectory(dir)) {
                    Files.list(dir).use { stream ->
                        stream.filter { it.toString().endsWith(".jsonl") }
                            .forEach { path ->
                                val mtime = Files.getLastModifiedTime(path).toMillis()
                                if (mtime > latestMtime) latestMtime = mtime
                            }
                    }
                }
            }
            if (latestMtime != lastKnownMtime && latestMtime > 0) {
                lastKnownMtime = latestMtime
                refresh()
            }
        } catch (_: Exception) {
        }

        // Refresh external session detection
        val changed = refreshExternalSessions()
        if (changed) {
            ApplicationManager.getApplication().invokeLater {
                listeners.forEach { it() }
            }
        }

        if (!pollAlarm.isDisposed) {
            pollAlarm.addRequest(::checkForChanges, 5000)
        }
    }

    /**
     * Returns true if external session state changed.
     *
     * Rate-limited: the detector scans the whole process table, and both the 5 s poll and
     * every list refresh want an answer. External terminals appear and disappear on human
     * timescales, so a floor of [EXTERNAL_SWEEP_MIN_MS] loses nothing.
     */
    private fun refreshExternalSessions(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastExternalSweep < EXTERNAL_SWEEP_MIN_MS) return false
        lastExternalSweep = now
        val ids = ClaudeProcessDetector.detectExternalSessions(project.basePath, cachedSessions ?: emptyList())
        val changed = ids != externalSessionIds
        externalSessionIds = ids
        return changed
    }

    private fun loadSessions(): List<SessionDisplay> {
        val basePath = project.basePath ?: run {
            log.warn("Clauditor: project.basePath is null — cannot discover sessions")
            return emptyList()
        }
        val candidateDirs = ClaudePathEncoder.projectDirCandidates(basePath)
        log.info("Clauditor session discovery: basePath=$basePath candidates=$candidateDirs")
        val seen = java.util.HashSet<Path>()
        val mainSessions = candidateDirs.flatMap { loadSessionsFromDir(it, null, seen) }
        val wtSessions = try {
            ClaudePathEncoder.worktreeNames(basePath).flatMap { name ->
                val wtProjectDir = ClaudePathEncoder.worktreeProjectDir(basePath, name)
                loadSessionsFromDir(wtProjectDir, name, seen)
            }
        } catch (_: Exception) { emptyList() }
        // Drop parse state for transcripts that are gone (deleted sessions, removed worktrees)
        // so their accumulators don't outlive them.
        accumulators.keys.retainAll(seen)
        return (mainSessions + wtSessions).sortedByDescending { it.modified }
    }

    private fun loadSessionsFromDir(
        projectDir: Path,
        worktreeName: String?,
        seen: MutableSet<Path>
    ): List<SessionDisplay> {
        if (!Files.isDirectory(projectDir)) return emptyList()

        val sessions = mutableListOf<SessionDisplay>()
        try {
            Files.list(projectDir).use { stream ->
                stream.filter { it.toString().endsWith(".jsonl") }
                    .forEach { path ->
                        seen.add(path)
                        try {
                            val session = parseJsonlSession(path, worktreeName)
                            if (session != null) sessions.add(session)
                        } catch (e: Exception) {
                            log.warn("Failed to parse session file: $path", e)
                        }
                    }
            }
        } catch (e: Exception) {
            log.error("Failed to list project directory: $projectDir", e)
        }
        return sessions.sortedByDescending { it.modified }
    }

    private fun parseJsonlSession(path: Path, worktreeName: String?): SessionDisplay? {
        val sessionId = path.fileName.toString().removeSuffix(".jsonl")
        val acc = accumulators.computeIfAbsent(path) { SessionAccumulator() }

        synchronized(acc) {
            try {
                acc.tail.consume(path, onRestart = { acc.reset() }) { line -> applyLine(acc, line) }
            } catch (e: Exception) {
                log.warn("Failed to read session file: $path", e)
            }

            val firstPrompt = acc.firstPrompt ?: return null

            // Use file modification time if no timestamp found in content
            val modified = if (acc.lastTimestamp == Instant.EPOCH) {
                Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
            } else {
                acc.lastTimestamp
            }

            return SessionDisplay(
                sessionId = sessionId,
                name = acc.customTitle,
                firstPrompt = firstPrompt,
                messageCount = acc.messageCount,
                modified = modified,
                gitBranch = acc.gitBranch,
                projectPath = project.basePath ?: "",
                worktreeName = worktreeName
            )
        }
    }

    /** Fold one transcript line into [acc]. Every update is order-independent or last-wins. */
    private fun applyLine(acc: SessionAccumulator, line: String) {
        try {
            val obj = gson.fromJson(line, JsonObject::class.java)
            val type = obj.get("type")?.asString

            if (type == "user" || type == "assistant") {
                acc.messageCount++
            }

            // /rename writes a custom-title entry; keep the last one
            if (type == "custom-title") {
                acc.customTitle = obj.get("customTitle")?.asString
            }

            // Extract first user prompt
            if (type == "user" && acc.firstPrompt == null) {
                val message = obj.getAsJsonObject("message")
                val content = message?.get("content")
                acc.firstPrompt = when {
                    content == null -> null
                    content.isJsonPrimitive -> content.asString
                    content.isJsonArray -> {
                        content.asJsonArray
                            .firstOrNull { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                            ?.asJsonObject?.get("text")?.asString
                    }
                    else -> null
                }

                // Grab git branch from first user message
                acc.gitBranch = obj.get("gitBranch")?.asString
            }

            // Track latest timestamp
            val ts = obj.get("timestamp")
            if (ts != null) {
                val instant = when {
                    ts.isJsonPrimitive && ts.asJsonPrimitive.isNumber ->
                        Instant.ofEpochMilli(ts.asLong)
                    ts.isJsonPrimitive && ts.asJsonPrimitive.isString ->
                        parseInstant(ts.asString)
                    else -> null
                }
                if (instant != null && instant > acc.lastTimestamp) {
                    acc.lastTimestamp = instant
                }
            }
        } catch (_: Exception) {
            // Skip malformed lines
        }
    }

    private fun parseInstant(dateStr: String): Instant {
        return try {
            Instant.parse(dateStr)
        } catch (_: Exception) {
            Instant.EPOCH
        }
    }


    override fun dispose() {
        listeners.clear()
    }

    companion object {
        /** Floor between `ps`-based external-session sweeps. */
        private const val EXTERNAL_SWEEP_MIN_MS = 4_000L

        fun getInstance(project: Project): ClaudeSessionService =
            project.getService(ClaudeSessionService::class.java)
    }
}
