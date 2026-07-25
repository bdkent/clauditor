package com.clauditor.toolwindow

import com.clauditor.util.ClaudePathEncoder
import com.clauditor.util.ProcessHelper
import java.nio.file.Files
import java.nio.file.Path

data class WorktreeEntry(
    val path: String,
    val branch: String?,
    val prunable: Boolean,
    val prunableReason: String?
)

/**
 * A worktree directory under `.claude/worktrees/` that has no Claude session associated
 * with it (all sessions deleted, created then abandoned, or created outside the plugin).
 * [registered] is true when git still tracks it as a worktree (the normal case);
 * false for a leftover directory git no longer knows about.
 */
data class OrphanWorktree(
    val name: String,
    val path: String,
    val branch: String?,
    val registered: Boolean
)

enum class WorktreeState { OK, REBASING, MERGING, CHERRY_PICKING, DETACHED, MISSING, UNKNOWN }

/**
 * Working-tree and branch state of one worktree, as reported by [WorktreeInspector.status].
 *
 * [ahead] is the number of commits on the worktree branch that are not reachable from
 * [mainBranch] — i.e. work that would be lost if the worktree were deleted. Note that a
 * branch merged by *squashing* still reads as ahead, because the squashed commit is a
 * different object; reachability can't see through it.
 */
data class WorktreeStatus(
    val state: WorktreeState,
    val name: String,
    val wtBranch: String? = null,
    val mainBranch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val dirtyFiles: List<String> = emptyList(),
    val errorDetail: String? = null
) {
    val dirtyCount: Int get() = dirtyFiles.size
    val dirty: Boolean get() = dirtyFiles.isNotEmpty()
}

enum class BaseBranchState { SYNCED, LOCAL_AHEAD, LOCAL_BEHIND, LOCAL_DIVERGED, OTHER_BRANCH, NO_REMOTE_DEFAULT, NO_LOCAL_DEFAULT, UNKNOWN }

data class BaseBranchInfo(
    val state: BaseBranchState,
    val currentBranch: String? = null,
    val defaultBranch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val errorDetail: String? = null
)

object WorktreeInspector {
    fun normalize(name: String): String = name.replace("/", "+")

    fun list(projectDir: String): List<WorktreeEntry> {
        return try {
            val res = ProcessHelper.execWithTimeout(
                command = arrayOf("git", "-C", projectDir, "worktree", "list", "--porcelain"),
                timeoutMs = 15_000,
                extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
            )
            parse(res.output)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun prune(projectDir: String): Pair<Boolean, String> {
        return try {
            val res = ProcessHelper.execWithTimeout(
                command = arrayOf("git", "-C", projectDir, "worktree", "prune"),
                timeoutMs = 15_000,
                extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
            )
            (res.exitCode == 0) to res.output
        } catch (e: Exception) {
            false to (e.message ?: "exception")
        }
    }

    /**
     * Worktree directories under `.claude/worktrees/` that have no session in [sessionWorktreeNames].
     * Joined against `git worktree list` so each carries its real branch and registration status.
     */
    fun findOrphans(projectDir: String, sessionWorktreeNames: Set<String>): List<OrphanWorktree> {
        val names = try {
            ClaudePathEncoder.worktreeNames(projectDir)
        } catch (_: Exception) {
            return emptyList()
        }
        val registered = list(projectDir)
        return names
            .filter { it !in sessionWorktreeNames }
            .map { name ->
                val entry = registered.firstOrNull { matchesWorktreeDir(it.path, name) }
                OrphanWorktree(
                    name = name,
                    path = entry?.path ?: ClaudePathEncoder.worktreeAbsolutePath(projectDir, name),
                    branch = entry?.branch,
                    registered = entry != null
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun matchesWorktreeDir(entryPath: String, name: String): Boolean =
        entryPath.replace('\\', '/').removeSuffix("/").endsWith("/.claude/worktrees/$name")

    /** Removes a registered worktree (directory + git registration). `--force` discards uncommitted changes. */
    fun remove(projectDir: String, worktreePath: String, force: Boolean): Pair<Boolean, String> {
        return try {
            val cmd = if (force) {
                arrayOf("git", "-C", projectDir, "worktree", "remove", "--force", worktreePath)
            } else {
                arrayOf("git", "-C", projectDir, "worktree", "remove", worktreePath)
            }
            val res = ProcessHelper.execWithTimeout(
                command = cmd,
                timeoutMs = 15_000,
                extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
            )
            (res.exitCode == 0) to res.output
        } catch (e: Exception) {
            false to (e.message ?: "exception")
        }
    }

    /**
     * Deletes [branch] only if it is fully merged — `git branch -d` refuses otherwise, so
     * unmerged commits are never silently dropped. Returns (deleted, git output).
     */
    fun deleteBranchIfMerged(projectDir: String, branch: String): Pair<Boolean, String> {
        return try {
            val res = ProcessHelper.execWithTimeout(
                command = arrayOf("git", "-C", projectDir, "branch", "-d", branch),
                timeoutMs = 15_000,
                extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
            )
            (res.exitCode == 0) to res.output
        } catch (e: Exception) {
            false to (e.message ?: "exception")
        }
    }

    /** Recursively deletes a leftover directory that git no longer tracks as a worktree. */
    fun deleteDirectory(path: String): Pair<Boolean, String> {
        return try {
            val dir = Path.of(path)
            if (Files.exists(dir)) {
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            true to ""
        } catch (e: Exception) {
            false to (e.message ?: "exception")
        }
    }

    fun inspectBaseBranch(projectDir: String): BaseBranchInfo {
        return try {
            // Prefer origin/HEAD (set on fresh clones), but many repos don't have it
            // configured even when origin/main clearly exists — fall back to probing.
            val defaultBranch = run {
                val (rc, out) = runGit(projectDir, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
                val fromHead = if (rc == 0) out.trim().removePrefix("origin/").ifEmpty { null } else null
                fromHead
                    ?: listOf("main", "master").firstOrNull { name ->
                        runGit(projectDir, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/$name").first == 0
                    }
                    ?: return BaseBranchInfo(
                        state = BaseBranchState.NO_REMOTE_DEFAULT,
                        errorDetail = "No origin/HEAD, origin/main, or origin/master found"
                    )
            }

            val (curRc, curOut) = runGit(projectDir, "branch", "--show-current")
            val current = if (curRc == 0) curOut.trim() else ""

            val (localRc, _) = runGit(projectDir, "rev-parse", "--verify", "--quiet", "refs/heads/$defaultBranch")
            if (localRc != 0) {
                return BaseBranchInfo(
                    state = BaseBranchState.NO_LOCAL_DEFAULT,
                    currentBranch = current.ifEmpty { null },
                    defaultBranch = defaultBranch
                )
            }

            if (current != defaultBranch) {
                return BaseBranchInfo(
                    state = BaseBranchState.OTHER_BRANCH,
                    currentBranch = current.ifEmpty { null },
                    defaultBranch = defaultBranch
                )
            }

            val (cntRc, cntOut) = runGit(projectDir, "rev-list", "--left-right", "--count", "refs/remotes/origin/$defaultBranch...refs/heads/$defaultBranch")
            val parts = if (cntRc == 0) cntOut.trim().split("\t") else emptyList()
            val behind = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val ahead = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val state = when {
                ahead > 0 && behind > 0 -> BaseBranchState.LOCAL_DIVERGED
                ahead > 0 -> BaseBranchState.LOCAL_AHEAD
                behind > 0 -> BaseBranchState.LOCAL_BEHIND
                else -> BaseBranchState.SYNCED
            }
            BaseBranchInfo(
                state = state,
                currentBranch = current,
                defaultBranch = defaultBranch,
                ahead = ahead,
                behind = behind
            )
        } catch (e: Exception) {
            BaseBranchInfo(state = BaseBranchState.UNKNOWN, errorDetail = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Branch/dirty state of a single worktree, relative to the branch currently checked out
     * in [projectPath] (the same comparison the worktree toolbar makes).
     *
     * Costs two to four short git invocations (~30-50 ms total). Call off the EDT.
     */
    fun status(worktreePath: String, projectPath: String): WorktreeStatus {
        val name = worktreePath.substringAfterLast('/')

        val wtPath = Path.of(worktreePath)
        if (!Files.isDirectory(wtPath)) {
            return WorktreeStatus(WorktreeState.MISSING, name, errorDetail = "Worktree directory not found at $worktreePath")
        }

        try {
            fun gitPathExists(arg: String): Boolean {
                val (rc, out) = runGit(worktreePath, "rev-parse", "--git-path", arg)
                if (rc != 0) return false
                val rel = out.trim()
                return rel.isNotEmpty() && Files.exists(wtPath.resolve(rel))
            }

            val inProgress = when {
                gitPathExists("rebase-merge") || gitPathExists("rebase-apply") -> WorktreeState.REBASING
                gitPathExists("MERGE_HEAD") -> WorktreeState.MERGING
                gitPathExists("CHERRY_PICK_HEAD") -> WorktreeState.CHERRY_PICKING
                else -> null
            }

            val (wtRc, wtOut) = runGit(worktreePath, "branch", "--show-current")
            if (wtRc != 0) {
                return WorktreeStatus(
                    state = WorktreeState.UNKNOWN,
                    name = name,
                    errorDetail = wtOut.trim().take(200).ifEmpty { "git branch --show-current failed" }
                )
            }
            val wtBranch = wtOut.trim()

            val (mainRc, mainOut) = runGit(projectPath, "branch", "--show-current")
            val mainBranch = if (mainRc == 0) mainOut.trim() else ""

            val (dirtyRc, dirtyOut) = runGit(worktreePath, "status", "--porcelain")
            // Porcelain v1: "XY <path>" — the path starts at column 3. Renames read
            // "R  old -> new"; keep the whole payload, it reads fine in a tooltip.
            val dirtyFiles = if (dirtyRc == 0) {
                dirtyOut.lines().filter { it.isNotBlank() }.map { it.drop(3).trim() }
            } else {
                emptyList()
            }

            if (inProgress != null) {
                return WorktreeStatus(
                    state = inProgress,
                    name = name,
                    wtBranch = wtBranch.ifEmpty { null },
                    mainBranch = mainBranch.ifEmpty { null },
                    dirtyFiles = dirtyFiles
                )
            }

            if (wtBranch.isEmpty() || mainBranch.isEmpty()) {
                return WorktreeStatus(
                    state = WorktreeState.DETACHED,
                    name = name,
                    wtBranch = wtBranch.ifEmpty { null },
                    mainBranch = mainBranch.ifEmpty { null },
                    dirtyFiles = dirtyFiles
                )
            }

            val (cntRc, cntOut) = runGit(worktreePath, "rev-list", "--left-right", "--count", "$mainBranch...$wtBranch")
            val parts = if (cntRc == 0) cntOut.trim().split("\t") else emptyList()
            val behind = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val ahead = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

            return WorktreeStatus(
                state = WorktreeState.OK,
                name = name,
                wtBranch = wtBranch,
                mainBranch = mainBranch,
                ahead = ahead,
                behind = behind,
                dirtyFiles = dirtyFiles
            )
        } catch (e: Exception) {
            return WorktreeStatus(
                state = WorktreeState.UNKNOWN,
                name = name,
                errorDetail = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun runGit(projectDir: String, vararg args: String): Pair<Int, String> {
        val res = ProcessHelper.execWithTimeout(
            command = arrayOf("git", "-C", projectDir, *args),
            timeoutMs = 15_000,
            extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
        )
        return res.exitCode to res.output
    }

    fun parse(output: String): List<WorktreeEntry> {
        val entries = mutableListOf<WorktreeEntry>()
        var path: String? = null
        var branch: String? = null
        var prunable = false
        var prunableReason: String? = null
        fun flush() {
            path?.let { entries.add(WorktreeEntry(it, branch, prunable, prunableReason)) }
            path = null; branch = null; prunable = false; prunableReason = null
        }
        for (line in output.lines()) {
            if (line.isBlank()) { flush(); continue }
            val parts = line.split(' ', limit = 2)
            when (parts[0]) {
                "worktree" -> path = parts.getOrNull(1)
                "branch" -> branch = parts.getOrNull(1)?.removePrefix("refs/heads/")
                "prunable" -> { prunable = true; prunableReason = parts.getOrNull(1) }
            }
        }
        flush()
        return entries
    }
}
