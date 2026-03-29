package com.clauditor.model

import java.time.Instant

data class SessionDisplay(
    val sessionId: String,
    val name: String?,
    val firstPrompt: String,
    val summary: String?,
    val messageCount: Int,
    val modified: Instant,
    val gitBranch: String?,
    val projectPath: String,
    val worktreeName: String? = null
) {
    val displayName: String
        get() = name
            ?: firstPrompt.take(60).let { if (firstPrompt.length > 60) "$it..." else it }

    val tabTitle: String
        get() = worktreeName ?: name ?: firstPrompt.take(40).let { if (firstPrompt.length > 40) "$it..." else it }
}
