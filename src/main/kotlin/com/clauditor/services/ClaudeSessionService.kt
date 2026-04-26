package com.clauditor.services

import com.clauditor.model.SessionDisplay
import com.clauditor.model.SessionIndex
import com.clauditor.util.ClaudePathEncoder
import com.clauditor.util.ClaudeProcessDetector
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
import java.io.BufferedReader
import java.io.FileReader
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

    @Volatile
    private var lastKnownMtime: Long = 0

    /** Session IDs running in external terminals (not Clauditor). */
    @Volatile
    private var externalSessionIds: Set<String> = emptySet()

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

        // Remove from sessions-index.json so it doesn't linger
        val indexPath = projectDir.resolve("sessions-index.json")
        if (Files.exists(indexPath)) {
            try {
                val json = Files.readString(indexPath)
                val index = gson.fromJson(json, SessionIndex::class.java)
                val filtered = index.entries.filter { it.sessionId != sessionId }
                if (filtered.size != index.entries.size) {
                    val updated = SessionIndex(index.version, filtered, index.originalPath)
                    Files.writeString(indexPath, gson.toJson(updated))
                }
            } catch (e: Exception) {
                log.warn("Failed to update sessions index: $indexPath", e)
            }
        }

        // Update cache directly — avoids race with concurrent background refresh overwriting
        // the corrected cache before listeners fire
        cachedSessions = cachedSessions?.filter { it.sessionId != sessionId }
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it() }
        }
        return true
    }

    fun refresh() {
        cachedSessions = loadSessions()
        refreshExternalSessions()
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it() }
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
                            path.endsWith("sessions-index.json") ||
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

    /** Returns true if external session state changed. */
    private fun refreshExternalSessions(): Boolean {
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
        val mainSessions = candidateDirs.flatMap { loadSessionsFromDir(it, null) }
        val wtSessions = try {
            ClaudePathEncoder.worktreeNames(basePath).flatMap { name ->
                val wtProjectDir = ClaudePathEncoder.worktreeProjectDir(basePath, name)
                loadSessionsFromDir(wtProjectDir, name)
            }
        } catch (_: Exception) { emptyList() }
        return (mainSessions + wtSessions).sortedByDescending { it.modified }
    }

    private fun loadSessionsFromDir(projectDir: Path, worktreeName: String?): List<SessionDisplay> {
        val indexPath = projectDir.resolve("sessions-index.json")
        if (Files.exists(indexPath)) {
            val result = loadFromIndex(indexPath, projectDir, worktreeName)
            if (result.isNotEmpty()) return result
            log.warn("sessions-index.json returned 0 sessions, falling back to JSONL scan for $projectDir")
        }
        return loadFromJsonlFiles(projectDir, worktreeName)
    }

    private fun loadFromIndex(indexPath: Path, projectDir: Path, worktreeName: String?): List<SessionDisplay> {
        return try {
            val json = Files.readString(indexPath)
            val index = gson.fromJson(json, SessionIndex::class.java)

            index.entries
                .filter { !it.isSidechain }
                .mapNotNull { entry ->
                    try {
                        val jsonlPath = projectDir.resolve("${entry.sessionId}.jsonl")
                        if (!Files.exists(jsonlPath)) return@mapNotNull null
                        SessionDisplay(
                            sessionId = entry.sessionId,
                            name = readCustomTitle(jsonlPath),
                            firstPrompt = entry.firstPrompt,
                            summary = entry.summary,
                            messageCount = entry.messageCount,
                            modified = parseInstant(entry.modified),
                            gitBranch = entry.gitBranch,
                            projectPath = entry.projectPath,
                            worktreeName = worktreeName
                        )
                    } catch (e: Exception) {
                        log.warn("Skipping session ${entry.sessionId}: ${e.message}")
                        null
                    }
                }
                .sortedByDescending { it.modified }
        } catch (e: Exception) {
            log.error("Failed to parse sessions-index.json at $indexPath: ${e.message}", e)
            emptyList()
        }
    }

    private fun loadFromJsonlFiles(projectDir: Path, worktreeName: String?): List<SessionDisplay> {
        if (!Files.isDirectory(projectDir)) return emptyList()

        val sessions = mutableListOf<SessionDisplay>()
        try {
            Files.list(projectDir).use { stream ->
                stream.filter { it.toString().endsWith(".jsonl") }
                    .forEach { path ->
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
        var firstPrompt: String? = null
        var customTitle: String? = null
        var messageCount = 0
        var gitBranch: String? = null
        var lastTimestamp: Instant = Instant.EPOCH

        BufferedReader(FileReader(path.toFile())).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val obj = gson.fromJson(line, JsonObject::class.java)
                    val type = obj.get("type")?.asString

                    if (type == "user" || type == "assistant") {
                        messageCount++
                    }

                    // /rename writes a custom-title entry; keep the last one
                    if (type == "custom-title") {
                        customTitle = obj.get("customTitle")?.asString
                    }

                    // Extract first user prompt
                    if (type == "user" && firstPrompt == null) {
                        val message = obj.getAsJsonObject("message")
                        val content = message?.get("content")
                        firstPrompt = when {
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
                        gitBranch = obj.get("gitBranch")?.asString
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
                        if (instant != null && instant > lastTimestamp) {
                            lastTimestamp = instant
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }

        if (firstPrompt == null) return null

        // Use file modification time if no timestamp found in content
        if (lastTimestamp == Instant.EPOCH) {
            lastTimestamp = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
        }

        return SessionDisplay(
            sessionId = sessionId,
            name = customTitle,
            firstPrompt = firstPrompt!!,
            summary = null,
            messageCount = messageCount,
            modified = lastTimestamp,
            gitBranch = gitBranch,
            projectPath = project.basePath ?: "",
            worktreeName = worktreeName
        )
    }

    /** Scans a JSONL file for the last custom-title entry written by /rename. */
    private fun readCustomTitle(path: Path): String? {
        if (!Files.exists(path)) return null
        var result: String? = null
        try {
            BufferedReader(FileReader(path.toFile())).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val obj = gson.fromJson(line, JsonObject::class.java)
                        if (obj.get("type")?.asString == "custom-title") {
                            result = obj.get("customTitle")?.asString
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return result
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
        fun getInstance(project: Project): ClaudeSessionService =
            project.getService(ClaudeSessionService::class.java)
    }
}
