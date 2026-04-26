package com.clauditor.util

import java.nio.file.Files
import java.nio.file.Path

object ClaudePathEncoder {

    fun encode(absolutePath: String): String = absolutePath.replace('/', '-')

    fun projectDir(projectBasePath: String): Path {
        val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
        return claudeHome.resolve("projects").resolve(encode(projectBasePath))
    }

    /**
     * Possible session-storage dirs for a given basePath. Claude Code's path encoder
     * replaces both `/` and `.` with `-`; ours only replaces `/` (changing it globally
     * historically broke external session detection — see commit 82818e5). For paths
     * containing dots (most commonly worktrees opened standalone via "Open in IDE"),
     * also probe the fully-encoded location so the new IDE finds its sessions.
     */
    fun projectDirCandidates(projectBasePath: String): List<Path> {
        val primary = projectDir(projectBasePath)
        if (!projectBasePath.contains('.')) return listOf(primary)
        val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
        val altEncoded = projectBasePath.replace('/', '-').replace('.', '-')
        val alt = claudeHome.resolve("projects").resolve(altEncoded)
        return if (alt == primary) listOf(primary) else listOf(primary, alt)
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

    /**
     * Project dir for a worktree session. Claude Code's path encoder replaces both / and .
     * with -, but our encode() only replaces / (which is correct for main project paths
     * that don't contain dots). Worktree paths go through .claude/worktrees/ so they
     * need the full encoding.
     */
    fun worktreeProjectDir(projectBasePath: String, worktreeName: String): Path {
        val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
        val wtPath = worktreeAbsolutePath(projectBasePath, worktreeName)
        val encoded = wtPath.replace('/', '-').replace('.', '-')
        return claudeHome.resolve("projects").resolve(encoded)
    }

    fun worktreeSessionsIndexPath(projectBasePath: String, worktreeName: String): Path =
        worktreeProjectDir(projectBasePath, worktreeName).resolve("sessions-index.json")
}
