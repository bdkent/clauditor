package com.clauditor.util

object ClaudeProcessDetector {

    private val SESSION_ID_REGEX = Regex(
        """--resume\s+([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""
    )

    /** Returns the set of session IDs currently running as live `claude --resume` processes. */
    fun getActiveSessionIds(): Set<String> {
        return try {
            val process = ProcessBuilder("ps", "-A", "-o", "args").start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            SESSION_ID_REGEX.findAll(output).map { it.groupValues[1] }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
