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
    val sevenDayResetsAt: Long?
)
