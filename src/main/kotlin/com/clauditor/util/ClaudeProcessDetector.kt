package com.clauditor.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

object ClaudeProcessDetector {

    private val gson = Gson()
    /**
     * Detects externally running claude sessions (not launched by Clauditor).
     *
     * Reads ~/.claude/sessions/{PID}.json to get session IDs directly.
     * If a session file has a "name" field, also resolves it against known sessions
     * (since --resume {name} may create a new session ID for the same named session).
     * Excludes Clauditor processes (identified by "clauditor-settings" in args).
     */
    fun detectExternalSessions(projectPath: String?, sessions: List<com.clauditor.model.SessionDisplay> = emptyList()): Set<String> {
        if (projectPath == null) return emptySet()
        return try {
            val clauditorPids = getClauditorPids()
            val sessionsDir = Path.of(System.getProperty("user.home"), ".claude", "sessions")
            if (!Files.isDirectory(sessionsDir)) return emptySet()

            val result = mutableSetOf<String>()
            Files.list(sessionsDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }
                    .forEach { path ->
                        try {
                            val obj = gson.fromJson(Files.readString(path), JsonObject::class.java)
                            val pid = obj.get("pid")?.asInt ?: return@forEach
                            val sessionId = obj.get("sessionId")?.asString ?: return@forEach
                            val cwd = obj.get("cwd")?.asString ?: return@forEach

                            if (cwd != projectPath || pid in clauditorPids || !isProcessAlive(pid)) return@forEach

                            result.add(sessionId)

                            // If the session file has a name, also mark all matching
                            // sessions from our list (--resume {name} gets a new ID,
                            // and multiple sessions can share a name)
                            val name = obj.get("name")?.asString
                            if (name != null) {
                                sessions.filter { it.name == name || it.tabTitle == name }
                                    .forEach { result.add(it.sessionId) }
                            }
                        } catch (_: Exception) {}
                    }
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun getClauditorPids(): Set<Int> {
        return try {
            val process = ProcessHelper.builder("ps", "-A", "-o", "pid,args", "-ww").start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines()
                .map { it.trim() }
                .filter { it.contains("clauditor-settings") }
                .mapNotNull { it.split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            val process = ProcessHelper.builder("kill", "-0", pid.toString()).start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
