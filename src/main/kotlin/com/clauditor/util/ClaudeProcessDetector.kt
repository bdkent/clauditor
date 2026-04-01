package com.clauditor.util

object ClaudeProcessDetector {

    private val UUID_REGEX = Regex(
        """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
    )
    private val RESUME_REGEX = Regex("""--resume\s+(\S+)""")

    /**
     * Detects externally running claude sessions (not launched by Clauditor).
     *
     * Returns session IDs that are running externally. For processes with --resume,
     * the value is resolved to a session ID (UUID or name lookup). For bare `claude`
     * processes, the cwd is checked and the most recently modified JSONL is used.
     */
    fun detectExternalSessions(projectPath: String?, sessions: List<com.clauditor.model.SessionDisplay> = emptyList()): Set<String> {
        return try {
            val process = ProcessHelper.builder("ps", "-A", "-o", "pid,args", "-ww").start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val result = mutableSetOf<String>()
            val unknownPids = mutableListOf<String>()

            for (line in output.lines()) {
                val trimmed = line.trim()
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) continue
                val pid = parts[0]
                val args = parts[1]
                if (!args.startsWith("claude")) continue
                if (args.contains("clauditor-settings")) continue

                val resumeMatch = RESUME_REGEX.find(args)
                if (resumeMatch != null) {
                    val value = resumeMatch.groupValues[1]
                    if (UUID_REGEX.matches(value)) {
                        // Direct session ID
                        result.add(value)
                    } else {
                        // Session name — resolve to ID
                        val session = sessions.find { it.name == value || it.tabTitle == value }
                        if (session != null) result.add(session.sessionId)
                    }
                } else if (args == "claude" || args.startsWith("claude ")) {
                    unknownPids.add(pid)
                }
            }

            // For bare `claude` processes, check cwd and infer session from most recent JSONL
            if (projectPath != null && unknownPids.isNotEmpty()) {
                for (pid in unknownPids) {
                    val cwd = getProcessCwd(pid)
                    if (cwd == projectPath) {
                        val sessionId = findMostRecentSession(projectPath)
                        if (sessionId != null) result.add(sessionId)
                    }
                }
            }

            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Returns the session ID of the most recently modified JSONL file in the project dir.
     * This is used to infer which session a bare `claude` process belongs to.
     */
    private fun findMostRecentSession(projectPath: String): String? {
        return try {
            val projectDir = ClaudePathEncoder.projectDir(projectPath)
            if (!java.nio.file.Files.isDirectory(projectDir)) return null
            java.nio.file.Files.list(projectDir).use { stream ->
                stream.filter { it.toString().endsWith(".jsonl") }
                    .max(Comparator.comparingLong { java.nio.file.Files.getLastModifiedTime(it).toMillis() })
                    .map { it.fileName.toString().removeSuffix(".jsonl") }
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getProcessCwd(pid: String): String? {
        return try {
            val lsof = ProcessHelper.builder("lsof", "-a", "-p", pid, "-d", "cwd", "-Fn").start()
            val output = lsof.inputStream.bufferedReader().readText()
            lsof.waitFor()
            output.lines().lastOrNull { it.startsWith("n/") }?.removePrefix("n")
        } catch (_: Exception) {
            null
        }
    }
}
