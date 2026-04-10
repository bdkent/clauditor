package com.clauditor.services

import com.clauditor.model.ClaudeStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

    private val gson = Gson()
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val monitoredSessions = ConcurrentHashMap<String, Path>()
    private val notifyFiles = ConcurrentHashMap<String, Path>()
    private val notifyState = ConcurrentHashMap<String, String>()
    private val currentStatus = ConcurrentHashMap<String, ClaudeStatus>()
    private val listeners = CopyOnWriteArrayList<(String, ClaudeStatus?) -> Unit>()
    private var wrapperScript: Path? = null
    private var notifyScript: Path? = null
    private val latestVersion = AtomicReference<String?>(null)
    @Volatile private var lastVersionCheck = 0L

    fun addStatusListener(listener: (String, ClaudeStatus?) -> Unit) {
        listeners.add(listener)
    }

    fun removeStatusListener(listener: (String, ClaudeStatus?) -> Unit) {
        listeners.remove(listener)
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
    fun getWrapperScriptPath(): Path {
        wrapperScript?.let { if (Files.exists(it)) return it }
        val script = Files.createTempFile("clauditor-statusline-", ".sh")
        Files.writeString(script, buildString {
            appendLine("#!/bin/bash")
            appendLine("input=\$(cat)")
            appendLine("if [ -n \"\$CLAUDITOR_STATUS_FILE\" ]; then")
            appendLine("  echo \"\$input\" > \"\$CLAUDITOR_STATUS_FILE\"")
            appendLine("fi")
            appendLine("if [ -n \"\$CLAUDITOR_ORIGINAL_STATUSLINE\" ]; then")
            appendLine("  echo \"\$input\" | \$CLAUDITOR_ORIGINAL_STATUSLINE")
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
            appendLine("    echo \"\$state\" > \"\${CLAUDITOR_NOTIFY_FILE}.tmp\"")
            appendLine("    mv \"\${CLAUDITOR_NOTIFY_FILE}.tmp\" \"\$CLAUDITOR_NOTIFY_FILE\"")
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
     * Discovers the user's configured statusLine command by reading the settings cascade:
     *   1. <project>/.claude/settings.local.json  (highest priority)
     *   2. <project>/.claude/settings.json
     *   3. ~/.claude/settings.local.json
     *   4. ~/.claude/settings.json              (lowest priority)
     */
    fun discoverOriginalStatusLineCommand(): String? {
        val basePath = project.basePath ?: return readGlobalStatusLineCommand()
        val paths = listOf(
            Path.of(basePath, ".claude", "settings.local.json"),
            Path.of(basePath, ".claude", "settings.json"),
            Path.of(System.getProperty("user.home"), ".claude", "settings.local.json"),
            Path.of(System.getProperty("user.home"), ".claude", "settings.json")
        )
        for (path in paths) {
            val cmd = readStatusLineCommand(path)
            if (cmd != null) return cmd
        }
        return null
    }

    private fun readGlobalStatusLineCommand(): String? {
        val home = System.getProperty("user.home")
        return readStatusLineCommand(Path.of(home, ".claude", "settings.local.json"))
            ?: readStatusLineCommand(Path.of(home, ".claude", "settings.json"))
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
                sevenDayResetsAt = sevenDay?.get("resets_at")?.asLong
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getLatestCliVersion(): String? = latestVersion.get()

    fun refreshLatestCliVersion() {
        val now = System.currentTimeMillis()
        if (now - lastVersionCheck < VERSION_CHECK_INTERVAL_MS) return
        lastVersionCheck = now
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = HttpRequests.request(GITHUB_LATEST_RELEASE_URL)
                    .connectTimeout(5000)
                    .readTimeout(5000)
                    .readString()
                val tag = gson.fromJson(json, JsonObject::class.java)
                    ?.get("tag_name")?.asString
                val version = tag?.removePrefix("v")
                if (version != null) latestVersion.set(version)
            } catch (_: Exception) {}
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
        private const val VERSION_CHECK_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        fun getInstance(project: Project): ClaudeStatusService =
            project.getService(ClaudeStatusService::class.java)
    }
}
