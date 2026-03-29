package com.clauditor.model

data class SessionMetadata(
    val pid: Int,
    val sessionId: String,
    val cwd: String,
    val startedAt: Long,
    val kind: String,
    val entrypoint: String,
    val name: String?
)
