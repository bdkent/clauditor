package com.clauditor.model

data class SessionIndexEntry(
    val sessionId: String,
    val fullPath: String,
    val fileMtime: Long,
    val firstPrompt: String,
    val summary: String?,
    val messageCount: Int,
    val created: String,
    val modified: String,
    val gitBranch: String?,
    val projectPath: String,
    val isSidechain: Boolean
)

data class SessionIndex(
    val version: Int,
    val entries: List<SessionIndexEntry>,
    val originalPath: String?
)
