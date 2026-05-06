package com.clauditor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "ClauditorSettings",
    storages = [Storage("clauditor.xml")]
)
@Service(Service.Level.APP)
class ClauditorSettings : PersistentStateComponent<ClauditorSettings.State> {

    class State {
        /** Model used for transient popup queries (Summarize, Explain Changes, Review Memory). */
        @JvmField var transientQueryModel: String = "sonnet"

        /** Milliseconds before a terminal session is considered unresponsive after user input. */
        @JvmField var echoTimeoutMs: Int = 3000

        /** Manual override for the claude binary path. Empty string means auto-detect. */
        @JvmField var claudeBinaryPath: String = ""

        /** Extra CLI arguments appended when launching new sessions (space-separated). */
        @JvmField var defaultSessionArgs: String = ""

        // --- Environment variable toggles ---
        @JvmField var envColorterm: Boolean = false
        @JvmField var envDisableNonessentialTraffic: Boolean = false
        @JvmField var envSkipUpdateCheck: Boolean = false
        @JvmField var envDisablePromptCaching: Boolean = false

        /** Additional custom env vars (KEY=VALUE per line). */
        @JvmField var customEnvVars: String = ""

        /** Status line refresh interval in seconds. 0 means event-driven only. */
        @JvmField var statusLineRefreshInterval: Int = 60

        /** Seconds between auto-refreshes of the git/worktree toolbar branch status. 0 = focus + status events only. */
        @JvmField var branchStatusRefreshSeconds: Int = 10
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Returns the configured binary path, or auto-detects via PATH if empty. */
    fun resolveClaudeBinary(): String {
        val override = myState.claudeBinaryPath.trim()
        if (override.isNotEmpty()) return override
        return com.clauditor.util.ProcessHelper.which("claude") ?: "claude"
    }

    /** Returns extra session args split into a list, or empty if none configured. */
    fun extraSessionArgs(): List<String> {
        val raw = myState.defaultSessionArgs.trim()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\\s+".toRegex())
    }

    /** Returns env vars to set based on toggle and custom settings. */
    fun environmentOverrides(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (myState.envColorterm) env["COLORTERM"] = "truecolor"
        if (myState.envDisableNonessentialTraffic) env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        if (myState.envSkipUpdateCheck) env["CLAUDE_CODE_SKIP_UPDATE_CHECK"] = "1"
        if (myState.envDisablePromptCaching) env["DISABLE_PROMPT_CACHING"] = "1"
        for (line in myState.customEnvVars.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.contains('=')) continue
            val key = trimmed.substringBefore('=')
            val value = trimmed.substringAfter('=')
            env[key] = value
        }
        return env
    }

    companion object {
        fun getInstance(): ClauditorSettings =
            ApplicationManager.getApplication().getService(ClauditorSettings::class.java)
    }
}
