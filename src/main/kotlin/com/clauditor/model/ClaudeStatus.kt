package com.clauditor.model

data class ClaudeStatus(
    val modelId: String?,
    val modelName: String?,
    val cliVersion: String?,
    val contextUsedPercent: Double?,
    val contextRemainingPercent: Double?,
    val contextRemainingTokens: Long?,
    val contextWindowSize: Long?,
    val costUsd: Double?,
    val fiveHourRatePercent: Double?,
    val fiveHourResetsAt: Long?,
    val sevenDayRatePercent: Double?,
    val sevenDayResetsAt: Long?,
    val effortLevel: String?,
    val thinkingEnabled: Boolean?,
    /**
     * The `session_id` reported by the statusline JSON — i.e. which session the terminal
     * is CURRENTLY showing. Used to detect an in-terminal session switch ("portal"), where
     * this diverges from the sessionId the tab was bound to.
     */
    val reportedSessionId: String? = null
)
