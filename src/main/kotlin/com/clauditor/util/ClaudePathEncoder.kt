package com.clauditor.util

import java.nio.file.Files
import java.nio.file.Path

object ClaudePathEncoder {

    fun encode(absolutePath: String): String = absolutePath.replace('/', '-').replace('.', '-')

    fun projectDir(projectBasePath: String): Path {
        val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
        return claudeHome.resolve("projects").resolve(encode(projectBasePath))
    }

    fun sessionsIndexPath(projectBasePath: String): Path =
        projectDir(projectBasePath).resolve("sessions-index.json")

    fun sessionsDir(): Path =
        Path.of(System.getProperty("user.home"), ".claude", "sessions")

    fun worktreeNames(projectBasePath: String): List<String> {
        val wtDir = Path.of(projectBasePath, ".claude", "worktrees")
        if (!Files.isDirectory(wtDir)) return emptyList()
        return Files.list(wtDir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .toList()
        }
    }

    fun worktreeAbsolutePath(projectBasePath: String, worktreeName: String): String =
        Path.of(projectBasePath, ".claude", "worktrees", worktreeName).toAbsolutePath().toString()
}
