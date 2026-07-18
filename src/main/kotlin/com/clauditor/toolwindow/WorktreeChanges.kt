package com.clauditor.toolwindow

import com.clauditor.util.ProcessHelper
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.NoDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import javax.swing.Action
import javax.swing.JComponent

/**
 * Computes the diff of a worktree, split into two groups:
 *  - COMMITTED: what the worktree branch changed relative to the base branch (merge-base..HEAD)
 *  - LOCAL: uncommitted changes in the working tree (tracked diff vs HEAD + untracked files)
 *
 * Changes are shown in a [SimpleChangesBrowser] — the same directory-tree + double-click-to-diff
 * component the Commit tool window uses — inside a modeless dialog with one tab per group.
 */
object WorktreeChanges {

    enum class Group(val label: String) { COMMITTED("Committed"), LOCAL("Uncommitted") }

    /**
     * A single changed file. A null side means that side is empty (added on the right, deleted on the left).
     * [workingVf] is the live working-tree file for LOCAL changes — resolved off the EDT so the diff can
     * be edited and saved back to disk. Null for committed/deleted sides (read-only).
     */
    data class FileChange(
        val group: Group,
        val path: String,
        val leftTitle: String,
        val rightTitle: String,
        val left: String?,
        val right: String?,
        val workingVf: VirtualFile? = null
    )

    data class Result(val changes: List<FileChange>, val error: String?)

    private fun git(dir: String, vararg args: String): ProcessHelper.ExecResult =
        ProcessHelper.execWithTimeout(
            command = arrayOf("git", "-C", dir, *args),
            timeoutMs = 15_000,
            extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
        )

    /** Contents of [path] at [rev], or null if git can't produce it (missing/binary/error). */
    private fun blob(dir: String, rev: String, path: String): String? {
        val res = git(dir, "show", "$rev:$path")
        return if (res.exitCode == 0) res.output else null
    }

    private fun workingFile(dir: String, rel: String): String? = try {
        val p = Path.of(dir).resolve(rel)
        if (Files.isRegularFile(p)) Files.readString(p) else null
    } catch (_: Exception) {
        null // binary or non-UTF8 working file
    }

    /** Resolve the live VirtualFile for a working-tree file. Off-EDT only (does a synchronous VFS refresh). */
    private fun resolveVf(dir: String, rel: String): VirtualFile? = try {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(dir).resolve(rel))
    } catch (_: Exception) {
        null
    }

    /** MUST be called off the EDT — runs several git invocations. */
    fun compute(worktreeDir: String): Result {
        val top = git(worktreeDir, "rev-parse", "--show-toplevel")
        if (top.exitCode != 0) return Result(emptyList(), "Not a git repository:\n${top.output.take(300)}")

        val base = detectBase(worktreeDir)
        val changes = mutableListOf<FileChange>()

        if (base != null) {
            val mergeBaseRes = git(worktreeDir, "merge-base", "HEAD", base)
            val mergeBase = if (mergeBaseRes.exitCode == 0) mergeBaseRes.output.trim().ifEmpty { null } else null
            if (mergeBase != null) {
                val res = git(worktreeDir, "diff", "--name-status", "-M", mergeBase, "HEAD")
                if (res.exitCode == 0) {
                    parseNameStatus(res.output).forEach { (status, oldPath, newPath) ->
                        changes += FileChange(
                            group = Group.COMMITTED,
                            path = newPath,
                            leftTitle = base,
                            rightTitle = "HEAD",
                            left = if (status == 'A') null else blob(worktreeDir, mergeBase, oldPath),
                            right = if (status == 'D') null else blob(worktreeDir, "HEAD", newPath)
                        )
                    }
                }
            }
        }

        val tracked = git(worktreeDir, "diff", "--name-status", "-M", "HEAD")
        if (tracked.exitCode == 0) {
            parseNameStatus(tracked.output).forEach { (status, oldPath, newPath) ->
                changes += FileChange(
                    group = Group.LOCAL,
                    path = newPath,
                    leftTitle = "HEAD",
                    rightTitle = "Working tree",
                    left = if (status == 'A') null else blob(worktreeDir, "HEAD", oldPath),
                    right = if (status == 'D') null else workingFile(worktreeDir, newPath),
                    workingVf = if (status == 'D') null else resolveVf(worktreeDir, newPath)
                )
            }
        }

        val untracked = git(worktreeDir, "ls-files", "--others", "--exclude-standard")
        if (untracked.exitCode == 0) {
            untracked.output.lines().filter { it.isNotBlank() }.forEach { p ->
                changes += FileChange(
                    group = Group.LOCAL,
                    path = p,
                    leftTitle = "HEAD",
                    rightTitle = "Working tree (new)",
                    left = null,
                    right = workingFile(worktreeDir, p),
                    workingVf = resolveVf(worktreeDir, p)
                )
            }
        }

        return Result(changes, if (changes.isEmpty()) "No changes in this worktree." else null)
    }

    /** MUST be called on the EDT — builds the changes browser and opens the dialog. */
    fun openDialog(project: Project, title: String, worktreeDir: String, changes: List<FileChange>) {
        val meta = IdentityHashMap<Change, FileChange>()
        fun toChange(c: FileChange): Change {
            val filePath = LocalFilePath(Path.of(worktreeDir, c.path).toString(), false)
            val before = c.left?.let { SimpleContentRevision(it, filePath, c.leftTitle) }
            val after = c.right?.let { SimpleContentRevision(it, filePath, c.rightTitle) }
            return Change(before, after).also { meta[it] = c }
        }
        val committed = changes.filter { it.group == Group.COMMITTED }.map(::toChange)
        val local = changes.filter { it.group == Group.LOCAL }.map(::toChange)
        WorktreeChangesDialog(project, title, committed, local, meta).show()
    }

    /** Prefer a local main/master; fall back to the remote default branch. Null if none found. */
    private fun detectBase(dir: String): String? {
        for (b in listOf("main", "master")) {
            if (git(dir, "rev-parse", "--verify", "--quiet", "refs/heads/$b").exitCode == 0) return b
        }
        val head = git(dir, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
        if (head.exitCode == 0) head.output.trim().ifEmpty { null }?.let { return it }
        for (b in listOf("origin/main", "origin/master")) {
            if (git(dir, "rev-parse", "--verify", "--quiet", "refs/remotes/$b").exitCode == 0) return b
        }
        return null
    }

    /** Parse `git diff --name-status -M` into (statusCode, oldPath, newPath). */
    private fun parseNameStatus(out: String): List<Triple<Char, String, String>> =
        out.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            val code = parts[0].firstOrNull() ?: return@mapNotNull null
            when (code) {
                'R', 'C' -> if (parts.size >= 3) Triple(code, parts[1], parts[2]) else null
                else -> if (parts.size >= 2) Triple(code, parts[1], parts[1]) else null
            }
        }
}

private class WorktreeChangesDialog(
    private val project: Project,
    dialogTitle: String,
    private val committed: List<Change>,
    private val local: List<Change>,
    private val meta: Map<Change, WorktreeChanges.FileChange>
) : DialogWrapper(project, true) {

    init {
        title = dialogTitle
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        if (committed.isNotEmpty()) tabs.addTab("Committed (${committed.size})", buildTab(committed))
        if (local.isNotEmpty()) tabs.addTab("Uncommitted (${local.size})", buildTab(local))
        tabs.preferredSize = JBUI.size(1000, 600)
        return tabs
    }

    /** Tree of changed files on the left, a live diff preview on the right (updates on selection). */
    private fun buildTab(changes: List<Change>): JComponent {
        // Directory-only grouping: the default "module" policy calls ProjectFileIndex.getModuleForFile,
        // which forces a workspace-index refresh on the EDT (SlowOperations violation). Build empty,
        // switch off module grouping, then populate so the rebuild never hits the module policy.
        val browser = SimpleChangesBrowser(project, emptyList())
        browser.viewer.groupingSupport.setGroupingKeysOrSkip(mutableSetOf("directory"))
        browser.setChangesToDisplay(changes)
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        diffPanel.setRequest(NoDiffRequest.INSTANCE)

        fun show(change: Change?) {
            diffPanel.setRequest(if (change == null) NoDiffRequest.INSTANCE else buildRequest(change))
        }
        browser.viewer.addTreeSelectionListener {
            show(VcsTreeModelData.selected(browser.viewer).userObjects(Change::class.java).firstOrNull())
        }
        if (changes.isNotEmpty()) show(changes.first())

        return JBSplitter(false, 0.3f).apply {
            firstComponent = browser
            secondComponent = diffPanel.component
            setHonorComponentsMinimumSize(true)
        }
    }

    private fun buildRequest(change: Change): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val fc = meta[change]
        val name = fc?.path?.substringAfterLast('/')
            ?: (change.afterRevision ?: change.beforeRevision)?.file?.name ?: "file"
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)

        // Left/base is always a historical snapshot — read-only.
        val left: DiffContent = fc?.left?.let { text ->
            factory.create(project, text, fileType).also { (it as? DocumentContent)?.document?.setReadOnly(true) }
        } ?: factory.createEmpty()

        // Right is the working file for the Uncommitted group: back it with the live VirtualFile
        // (pre-resolved off the EDT) so edits in the diff save straight to disk. Committed/base
        // sides stay read-only text.
        val right: DiffContent = when {
            fc == null -> factory.createEmpty()
            fc.group == WorktreeChanges.Group.LOCAL && fc.workingVf != null ->
                factory.create(project, fc.workingVf)
            fc.right != null -> factory.create(project, fc.right, fileType)
                .also { (it as? DocumentContent)?.document?.setReadOnly(true) }
            else -> factory.createEmpty()
        }
        return SimpleDiffRequest(name, left, right, fc?.leftTitle.orEmpty(), fc?.rightTitle.orEmpty())
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun getDimensionServiceKey(): String = "com.clauditor.WorktreeChangesDialog"
}
