package com.clauditor.services

import com.clauditor.model.ClaudeStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ClaudeStatusService(private val project: Project) : Disposable {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ClaudeStatusService::class.java)
    private val gson = Gson()
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val monitoredSessions = ConcurrentHashMap<String, Path>()
    private val notifyFiles = ConcurrentHashMap<String, Path>()
    private val notifyState = ConcurrentHashMap<String, String>()
    private val currentStatus = ConcurrentHashMap<String, ClaudeStatus>()
    private val listeners = CopyOnWriteArrayList<(String, ClaudeStatus?) -> Unit>()
    private var wrapperScript: Path? = null
    private var notifyScript: Path? = null
    private val installedVersion = AtomicReference<String?>(null)
    private val publishedVersion = AtomicReference<String?>(null)
    @Volatile private var lastInstalledCheck = 0L
    @Volatile private var lastPublishedCheck = 0L
    private val installedRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val publishedRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun addStatusListener(listener: (String, ClaudeStatus?) -> Unit) {
        listeners.add(listener)
        log.info("Clauditor[${project.name}]: addStatusListener — listener count now ${listeners.size}")
    }

    fun removeStatusListener(listener: (String, ClaudeStatus?) -> Unit) {
        listeners.remove(listener)
        log.info("Clauditor[${project.name}]: removeStatusListener — listener count now ${listeners.size}")
    }

    fun getStatus(sessionId: String): ClaudeStatus? = currentStatus[sessionId]

    fun getAllStatuses(): Map<String, ClaudeStatus> = HashMap(currentStatus)

    fun getNotifyState(sessionId: String): String? = notifyState[sessionId]

    fun clearNotifyState(sessionId: String) {
        notifyState.remove(sessionId)
        notifyFiles[sessionId]?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
    }

    /**
     * Returns the path to the wrapper script, creating it on first call.
     * The wrapper reads JSON from stdin, writes it to $CLAUDITOR_STATUS_FILE,
     * then chains to $CLAUDITOR_ORIGINAL_STATUSLINE (the user's real command).
     */
    @Synchronized
    fun getWrapperScriptPath(): Path {
        wrapperScript?.let { if (Files.exists(it)) return it }
        val script = Files.createTempFile("clauditor-statusline-", ".sh")
        Files.writeString(script, buildString {
            appendLine("#!/bin/bash")
            appendLine("input=\$(cat)")
            appendLine("if [ -n \"\$CLAUDITOR_STATUS_FILE\" ]; then")
            appendLine("  echo \"\$input\" > \"\${CLAUDITOR_STATUS_FILE}.\$\$\"")
            appendLine("  mv \"\${CLAUDITOR_STATUS_FILE}.\$\$\" \"\$CLAUDITOR_STATUS_FILE\" 2>/dev/null")
            appendLine("fi")
            appendLine("if [ -n \"\$CLAUDITOR_ORIGINAL_STATUSLINE\" ]; then")
            // Run through eval so the user's command is shell-parsed the way Claude
            // itself runs it — honoring tilde, \$VARs, quotes, and pipes. A bare
            // `\$CLAUDITOR_ORIGINAL_STATUSLINE` only word-splits/globs, which breaks
            // any command using ~, \$HOME, quoted paths, or pipes.
            appendLine("  echo \"\$input\" | eval \"\$CLAUDITOR_ORIGINAL_STATUSLINE\"")
            appendLine("fi")
        })
        script.toFile().setExecutable(true)
        wrapperScript = script
        return script
    }

    /**
     * Returns the path to the notify wrapper script, creating it on first call.
     * Hook commands call this script, which reads the event JSON from stdin,
     * extracts the notification_type, and writes it to $CLAUDITOR_NOTIFY_FILE.
     */
    @Synchronized
    fun getNotifyScriptPath(): Path {
        notifyScript?.let { if (Files.exists(it)) return it }
        val script = Files.createTempFile("clauditor-notify-", ".sh")
        Files.writeString(script, buildString {
            appendLine("#!/bin/bash")
            appendLine("input=\$(cat)")
            appendLine("if [ -n \"\$CLAUDITOR_NOTIFY_FILE\" ]; then")
            appendLine("  event=\$(echo \"\$input\" | sed -n 's/.*\"hook_event_name\":\"\\([^\"]*\\)\".*/\\1/p')")
            appendLine("  state=\"\"")
            appendLine("  case \"\$event\" in")
            appendLine("    Notification)")
            appendLine("      state=\$(echo \"\$input\" | sed -n 's/.*\"notification_type\":\"\\([^\"]*\\)\".*/\\1/p')")
            appendLine("      ;;")
            appendLine("    PreToolUse)")
            appendLine("      tool=\$(echo \"\$input\" | sed -n 's/.*\"tool_name\":\"\\([^\"]*\\)\".*/\\1/p')")
            appendLine("      state=\"tool:\$tool\"")
            appendLine("      ;;")
            appendLine("    PostToolUse|PostToolUseFailure)")
            appendLine("      state=\"clear\"")
            appendLine("      ;;")
            appendLine("    PreCompact)")
            appendLine("      state=\"compact\"")
            appendLine("      ;;")
            appendLine("    PostCompact)")
            appendLine("      state=\"clear\"")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("  if [ -n \"\$state\" ]; then")
            appendLine("    echo \"\$state\" > \"\${CLAUDITOR_NOTIFY_FILE}.\$\$\"")
            appendLine("    mv \"\${CLAUDITOR_NOTIFY_FILE}.\$\$\" \"\$CLAUDITOR_NOTIFY_FILE\" 2>/dev/null")
            appendLine("  fi")
            appendLine("fi")
        })
        script.toFile().setExecutable(true)
        notifyScript = script
        return script
    }

    /**
     * Creates a temporary settings file that overrides statusLine.command
     * and adds Notification hooks for idle/permission detection.
     */
    fun createOverrideSettingsFile(wrapperPath: Path, notifyPath: Path): Path {
        val file = Files.createTempFile("clauditor-settings-", ".json")
        val root = JsonObject()

        val statusLine = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", wrapperPath.toAbsolutePath().toString())
            val refreshInterval = com.clauditor.settings.ClauditorSettings.getInstance().state.statusLineRefreshInterval
            if (refreshInterval > 0) {
                addProperty("refreshInterval", refreshInterval)
            }
        }
        root.add("statusLine", statusLine)

        val notifyCmd = notifyPath.toAbsolutePath().toString()
        val hookEntry = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", notifyCmd)
            })
        }

        val hooks = JsonObject()

        // Notification hooks (idle/permission)
        val notificationRules = JsonArray()
        for (type in listOf("idle_prompt", "permission_prompt")) {
            notificationRules.add(JsonObject().apply {
                addProperty("matcher", type)
                add("hooks", hookEntry.deepCopy())
            })
        }
        hooks.add("Notification", notificationRules)

        // Tool use hooks (all tools)
        val toolRule = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("matcher", ".*")
                add("hooks", hookEntry.deepCopy())
            })
        }
        hooks.add("PreToolUse", toolRule)
        hooks.add("PostToolUse", toolRule.deepCopy())
        hooks.add("PostToolUseFailure", toolRule.deepCopy())

        // Compact hooks (no matcher needed)
        val compactRule = JsonArray().apply {
            add(JsonObject().apply {
                add("hooks", hookEntry.deepCopy())
            })
        }
        hooks.add("PreCompact", compactRule)
        hooks.add("PostCompact", compactRule.deepCopy())

        root.add("hooks", hooks)

        Files.writeString(file, gson.toJson(root) + "\n")
        return file
    }

    fun createStatusFilePath(sessionId: String): Path {
        return Path.of(System.getProperty("java.io.tmpdir"), "clauditor-status-$sessionId.json")
    }

    fun createNotifyFilePath(sessionId: String): Path {
        return Path.of(System.getProperty("java.io.tmpdir"), "clauditor-notify-$sessionId")
    }

    /**
     * Discovers the user's configured statusLine command by reading the settings
     * cascade in Claude Code's own precedence order (highest priority first):
     *   1. Enterprise-managed policy settings (managed-settings.json)
     *   2. <project>/.claude/settings.local.json
     *   3. <project>/.claude/settings.json
     *   4. ~/.claude/settings.local.json
     *   5. ~/.claude/settings.json
     */
    fun discoverOriginalStatusLineCommand(): String? {
        for (path in settingsCascadePaths()) {
            val cmd = readStatusLineCommand(path)
            if (cmd != null) return cmd
        }
        return null
    }

    /** Ordered settings files Clauditor consults to find the real statusLine command, highest priority first. */
    private fun settingsCascadePaths(): List<Path> {
        val home = System.getProperty("user.home")
        val paths = mutableListOf<Path>()
        managedSettingsPath()?.let { paths.add(it) }
        project.basePath?.let { base ->
            paths.add(Path.of(base, ".claude", "settings.local.json"))
            paths.add(Path.of(base, ".claude", "settings.json"))
        }
        paths.add(Path.of(home, ".claude", "settings.local.json"))
        paths.add(Path.of(home, ".claude", "settings.json"))
        return paths
    }

    /**
     * Path to the OS-level enterprise-managed Claude settings, which take precedence
     * over all user/project settings. Returns null on unrecognized platforms.
     */
    private fun managedSettingsPath(): Path? {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                Path.of("/Library/Application Support/ClaudeCode/managed-settings.json")
            os.contains("win") ->
                Path.of(System.getenv("ProgramData") ?: "C:\\ProgramData", "ClaudeCode", "managed-settings.json")
            else ->
                Path.of("/etc/claude-code/managed-settings.json")
        }
    }

    private fun readStatusLineCommand(path: Path): String? {
        if (!Files.exists(path)) return null
        return try {
            val obj = gson.fromJson(Files.readString(path), JsonObject::class.java)
            val cmd = obj.getAsJsonObject("statusLine")?.get("command")?.asString
            if (cmd.isNullOrBlank()) null else cmd
        } catch (_: Exception) {
            null
        }
    }

    fun startMonitoring(sessionId: String, statusFile: Path, notifyFile: Path) {
        monitoredSessions[sessionId] = statusFile
        notifyFiles[sessionId] = notifyFile
        if (monitoredSessions.size == 1) schedulePoll()
    }

    fun stopMonitoring(sessionId: String) {
        monitoredSessions.remove(sessionId)
        notifyFiles.remove(sessionId)
        // Keep currentStatus, notifyState, and /tmp files around so a re-instantiated
        // editor can show the last known state immediately. Use clearSession() for
        // explicit tab-close cleanup, and service dispose() handles project close.
    }

    /** Fully clear all state for a session — call when the user explicitly closes the tab. */
    fun clearSession(sessionId: String) {
        stopMonitoring(sessionId)
        notifyState.remove(sessionId)
        currentStatus.remove(sessionId)
        try { Files.deleteIfExists(createStatusFilePath(sessionId)) } catch (_: Exception) {}
        try { Files.deleteIfExists(createNotifyFilePath(sessionId)) } catch (_: Exception) {}
    }

    private fun schedulePoll() {
        if (pollAlarm.isDisposed || monitoredSessions.isEmpty()) return
        pollAlarm.addRequest(::poll, 500)
    }

    private fun poll() {
        for ((sessionId, statusFile) in monitoredSessions) {
            try {
                if (Files.exists(statusFile)) {
                    val json = Files.readString(statusFile)
                    val status = parseStatus(json)
                    if (status != null && status != currentStatus[sessionId]) {
                        currentStatus[sessionId] = status
                        log.info("Clauditor[${project.name}]: poll — status changed for $sessionId, firing ${listeners.size} listener(s)")
                        listeners.forEach { it(sessionId, status) }
                    }
                }
            } catch (_: Exception) {}
        }
        for ((sessionId, notifyFile) in notifyFiles) {
            try {
                if (Files.exists(notifyFile)) {
                    val type = Files.readString(notifyFile).trim()
                    if (type == "clear") {
                        Files.deleteIfExists(notifyFile)
                        if (notifyState.remove(sessionId) != null) {
                            listeners.forEach { it(sessionId, currentStatus[sessionId]) }
                        }
                    } else if (type.isNotEmpty() && notifyState.put(sessionId, type) != type) {
                        listeners.forEach { it(sessionId, currentStatus[sessionId]) }
                    }
                }
            } catch (_: Exception) {}
        }
        schedulePoll()
    }

    private fun parseStatus(json: String): ClaudeStatus? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val modelObj = obj.getAsJsonObject("model")
            val modelId = modelObj?.get("id")?.asString
            val modelName = modelObj?.get("display_name")?.asString
            val version = obj.get("version")?.asString
            val ctx = obj.getAsJsonObject("context_window")
            val ctxSize = ctx?.get("context_window_size")?.asLong
            val usage = ctx?.getAsJsonObject("current_usage")
            val usedTokens = if (usage != null) {
                (usage.get("input_tokens")?.asLong ?: 0) +
                    (usage.get("cache_creation_input_tokens")?.asLong ?: 0) +
                    (usage.get("cache_read_input_tokens")?.asLong ?: 0)
            } else null
            val remainingTokens = if (ctxSize != null && usedTokens != null) ctxSize - usedTokens else null
            val cost = obj.getAsJsonObject("cost")
            val costUsd = cost?.get("total_cost_usd")?.asDouble
            val rates = obj.getAsJsonObject("rate_limits")
            val fiveHour = rates?.getAsJsonObject("five_hour")
            val sevenDay = rates?.getAsJsonObject("seven_day")
            val effort = obj.getAsJsonObject("effort")
            val thinking = obj.getAsJsonObject("thinking")
            ClaudeStatus(
                modelId = modelId,
                modelName = modelName,
                cliVersion = version,
                contextUsedPercent = ctx?.get("used_percentage")?.asDouble,
                contextRemainingPercent = ctx?.get("remaining_percentage")?.asDouble,
                contextRemainingTokens = remainingTokens,
                contextWindowSize = ctxSize,
                costUsd = costUsd,
                fiveHourRatePercent = fiveHour?.get("used_percentage")?.asDouble,
                fiveHourResetsAt = fiveHour?.get("resets_at")?.asLong,
                sevenDayRatePercent = sevenDay?.get("used_percentage")?.asDouble,
                sevenDayResetsAt = sevenDay?.get("resets_at")?.asLong,
                effortLevel = effort?.get("level")?.asString,
                thinkingEnabled = thinking?.get("enabled")?.asBoolean
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getInstalledCliVersion(): String? = installedVersion.get()

    fun getPublishedCliVersion(): String? = publishedVersion.get()

    fun refreshVersions() {
        refreshInstalled()
        refreshPublished()
    }

    private fun refreshInstalled() {
        val now = System.currentTimeMillis()
        if (now - lastInstalledCheck < INSTALLED_CHECK_INTERVAL_MS) return
        if (!installedRefreshInFlight.compareAndSet(false, true)) return
        lastInstalledCheck = now
        com.clauditor.util.ClauditorExecutor.submit {
            try {
                val res = com.clauditor.util.ProcessHelper.execWithTimeout(
                    command = arrayOf("claude", "--version"),
                    timeoutMs = 5_000
                )
                if (res.exitCode == 0 && !res.timedOut) {
                    val v = res.output.trim().substringBefore(' ').ifBlank { null }
                    if (v != null) installedVersion.set(v)
                }
            } catch (_: Exception) {
            } finally {
                installedRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshPublished() {
        val now = System.currentTimeMillis()
        if (now - lastPublishedCheck < PUBLISHED_CHECK_INTERVAL_MS) return
        if (!publishedRefreshInFlight.compareAndSet(false, true)) return
        lastPublishedCheck = now
        com.clauditor.util.ClauditorExecutor.submit {
            try {
                val json = HttpRequests.request(GITHUB_LATEST_RELEASE_URL)
                    .connectTimeout(5000)
                    .readTimeout(5000)
                    .readString()
                val tag = gson.fromJson(json, JsonObject::class.java)
                    ?.get("tag_name")?.asString
                val version = tag?.removePrefix("v")
                if (version != null) publishedVersion.set(version)
            } catch (_: Exception) {
            } finally {
                publishedRefreshInFlight.set(false)
            }
        }
    }

    override fun dispose() {
        wrapperScript?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        notifyScript?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        for (sessionId in monitoredSessions.keys) {
            try { Files.deleteIfExists(createStatusFilePath(sessionId)) } catch (_: Exception) {}
            try { Files.deleteIfExists(createNotifyFilePath(sessionId)) } catch (_: Exception) {}
        }
        monitoredSessions.clear()
        notifyFiles.clear()
        notifyState.clear()
        currentStatus.clear()
        listeners.clear()
    }

    companion object {
        private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/anthropics/claude-code/releases/latest"
        private const val INSTALLED_CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val PUBLISHED_CHECK_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        fun getInstance(project: Project): ClaudeStatusService =
            project.getService(ClaudeStatusService::class.java)
    }
}
