package com.clauditor.services

import com.clauditor.model.ScratchpadFile
import com.clauditor.model.ScratchpadGroup
import com.clauditor.util.ClaudePathEncoder
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Locates the per-session scratchpad directories Claude Code creates under its temp root.
 *
 * Unlike memory (one project-scoped dir that persists), a scratchpad is session-scoped and
 * ephemeral, holding whatever working files a session produced — logs, probes, captures.
 */
@Service(Service.Level.PROJECT)
class ScratchpadService(private val project: Project) {

    private val log = Logger.getInstance(ScratchpadService::class.java)

    /**
     * Scratchpad groups for this project's sessions, newest file first within each group and
     * the current session first overall. Sessions with an empty or absent scratchpad are omitted.
     */
    fun scan(currentSessionId: String?): List<ScratchpadGroup> {
        val basePath = project.basePath ?: return emptyList()

        // Session titles are only for labelling — discovery is driven by the directories
        // themselves, so a scratchpad outlives deletion of its session transcript.
        val labels = try {
            ClaudeSessionService.getInstance(project).getSessions()
                .associate { it.sessionId to it.tabTitle }
        } catch (e: Exception) {
            log.warn("Failed to list sessions for scratchpad labels", e)
            emptyMap()
        }

        val groups = mutableListOf<ScratchpadGroup>()
        for (dir in scratchpadDirs(basePath)) {
            val sessionId = dir.parent?.fileName?.toString() ?: continue
            val (files, truncated) = listFiles(dir)
            if (files.isEmpty()) continue

            groups.add(
                ScratchpadGroup(
                    sessionId = sessionId,
                    label = labels[sessionId] ?: "${sessionId.take(8)} (no session)",
                    isCurrent = sessionId == currentSessionId,
                    dir = dir,
                    files = files,
                    truncated = truncated
                )
            )
        }

        // Current session first, then most-recently-touched scratchpad.
        return groups.sortedWith(
            compareByDescending<ScratchpadGroup> { it.isCurrent }
                .thenByDescending { it.files.firstOrNull()?.modified ?: Instant.EPOCH }
        )
    }

    /**
     * Every existing `<tempRoot>/<encoded-cwd>/<sessionId>/scratchpad` for this project and its
     * worktrees, found by listing the per-project temp dirs rather than probing per session.
     */
    private fun scratchpadDirs(basePath: String): List<Path> {
        val cwds = mutableListOf(basePath)
        try {
            ClaudePathEncoder.worktreeNames(basePath).forEach {
                cwds.add(ClaudePathEncoder.worktreeAbsolutePath(basePath, it))
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate worktrees for scratchpad scan", e)
        }

        val projectTempDirs = cwds.flatMap { cwd ->
            // scratchpadCandidates appends "<sessionId>/scratchpad"; we want the dir above both.
            ClaudePathEncoder.scratchpadCandidates(cwd, "x").mapNotNull { it.parent?.parent }
        }.distinct()

        val out = mutableListOf<Path>()
        for (projectTemp in projectTempDirs) {
            if (!Files.isDirectory(projectTemp)) continue
            try {
                Files.list(projectTemp).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .map { it.resolve("scratchpad") }
                        .filter { Files.isDirectory(it) }
                        .forEach { out.add(it) }
                }
            } catch (e: Exception) {
                log.warn("Failed to list session temp dirs: $projectTemp", e)
            }
        }
        return out
    }

    /** Walks one scratchpad dir, newest first, bounded in both depth and count. */
    private fun listFiles(dir: Path): Pair<List<ScratchpadFile>, Boolean> {
        val out = mutableListOf<ScratchpadFile>()
        var truncated = false
        try {
            Files.walk(dir, MAX_DEPTH).use { stream ->
                for (path in stream) {
                    if (!Files.isRegularFile(path)) continue
                    if (out.size >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    out.add(
                        ScratchpadFile(
                            name = dir.relativize(path).toString(),
                            path = path,
                            size = runCatching { Files.size(path) }.getOrDefault(0L),
                            modified = runCatching { Files.getLastModifiedTime(path).toInstant() }
                                .getOrDefault(Instant.EPOCH)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to walk scratchpad dir: $dir", e)
        }
        return out.sortedByDescending { it.modified } to truncated
    }

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 2000

        fun getInstance(project: Project): ScratchpadService =
            project.getService(ScratchpadService::class.java)
    }
}
