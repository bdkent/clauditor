package com.clauditor.toolwindow

import com.clauditor.model.SessionDisplay
import com.clauditor.model.SessionStatus
import com.clauditor.services.ClaudeSessionService
import com.clauditor.util.ClaudePathEncoder
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.TableCellRenderer

class SessionListPanel(
    private val project: Project,
    private val sessionService: ClaudeSessionService,
    private val getStatus: (String) -> SessionStatus,
    private val onSessionSelected: (SessionDisplay) -> Unit,
    private val onNewSession: (name: String) -> Unit,
    private val onForkSession: (SessionDisplay) -> Unit,
    private val worktreeMode: Boolean = false
) : Disposable {

    private val tableModel = SessionTableModel(getStatus, showWorktreeColumn = worktreeMode)
    private val table = object : TableView<SessionDisplay>(tableModel) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
            val c = super.prepareRenderer(renderer, row, column)
            val session = tableModel.getItem(convertRowIndexToModel(row))
            when (getStatus(session.sessionId)) {
                SessionStatus.OPEN_EXTERNALLY -> {
                    c.foreground = if (isRowSelected(row)) selectionForeground
                                   else UIUtil.getLabelDisabledForeground()
                    toolTipText = "This session is open in an external terminal"
                }
                SessionStatus.OPEN_IN_PLUGIN -> {
                    c.foreground = if (isRowSelected(row)) selectionForeground else foreground
                    toolTipText = "This session is open \u2014 double-click to focus"
                }
                SessionStatus.AVAILABLE -> {
                    c.foreground = if (isRowSelected(row)) selectionForeground else foreground
                    toolTipText = null
                }
            }
            return c
        }
    }
    private val rootPanel = JPanel(BorderLayout())
    private val searchField = SearchTextField(false)

    private var allSessions: List<SessionDisplay> = emptyList()
    private val changeListener: () -> Unit = { reloadData() }

    // Orphaned-worktree section (worktree mode only): dirs under .claude/worktrees/ with no session.
    private val orphanListModel = javax.swing.DefaultListModel<OrphanWorktree>()
    private val orphanList = com.intellij.ui.components.JBList(orphanListModel)
    private val orphanPanel = JPanel(BorderLayout())
    private var orphanHeader: com.intellij.ui.components.JBLabel? = null
    private val orphanRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val tableScroll: JComponent = ScrollPaneFactory.createScrollPane(table)
    private val centerPanel = JPanel(BorderLayout())
    // Table on top, orphan list on bottom; user-draggable so orphans never crowd out sessions.
    private val orphanSplitter = com.intellij.ui.OnePixelSplitter(true, 0.7f)

    init {
        setupTable()
        setupLayout()
        setupDoubleClick()
        setupContextMenu()
        sessionService.addChangeListener(changeListener)
        ApplicationManager.getApplication().invokeLater { reloadData() }
    }

    fun getComponent(): JComponent = rootPanel

    private fun setupTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(24)

        // 3-state click cycle: asc → desc → unsorted (= model order, which the service already
        // returns sortedByDescending { modified } so unsorted == MRU).
        val sorter = object : javax.swing.table.TableRowSorter<SessionTableModel>(tableModel) {
            override fun toggleSortOrder(column: Int) {
                val keys = sortKeys
                if (keys.size == 1 && keys[0].column == column &&
                    keys[0].sortOrder == javax.swing.SortOrder.DESCENDING) {
                    sortKeys = emptyList()
                } else {
                    super.toggleSortOrder(column)
                }
            }
        }
        sorter.setSortable(0, false)  // status icon column: inert
        sorter.setComparator(1, String.CASE_INSENSITIVE_ORDER)  // Name
        if (worktreeMode) {
            sorter.setComparator(2, String.CASE_INSENSITIVE_ORDER)  // Worktree
        }
        // Last Used and Msgs need no explicit comparator: their ColumnInfos declare Long/Integer
        // via getColumnClass(), so TableRowSorter uses natural Comparable ordering. (Declaring the
        // class is required — without it the default String class makes the sorter pick a Collator
        // that throws ClassCastException on the numeric values and silently aborts the sort.)
        table.rowSorter = sorter

        val cm = table.columnModel
        if (worktreeMode) {
            cm.getColumn(1).preferredWidth = 180  // Name
            cm.getColumn(2).preferredWidth = 140  // Worktree
            cm.getColumn(3).preferredWidth = 110  // Last Used
            cm.getColumn(4).preferredWidth = 50   // Msgs
        } else {
            cm.getColumn(1).preferredWidth = 240  // Name
            cm.getColumn(2).preferredWidth = 110  // Last Used
            cm.getColumn(3).preferredWidth = 50   // Msgs
        }
    }

    private fun setupLayout() {
        val topBar = JPanel(BorderLayout(JBUI.scale(4), 0))

        val (actionTitle, actionDesc, actionIcon, dialogPrompt, dialogTitle) = if (worktreeMode) {
            ActionMeta("New Worktree", "Start a new Claude session in a worktree", com.clauditor.editor.ClaudeSessionIconProvider.TREE_ICON, "Worktree name:", "New Worktree Session")
        } else {
            ActionMeta("New Session", "Start a new Claude session", AllIcons.General.Add, "Session name:", "New Claude Session")
        }
        val newSessionAction = object : AnAction(actionTitle, actionDesc, actionIcon) {
            override fun actionPerformed(e: AnActionEvent) {
                if (worktreeMode) {
                    val dialog = WorktreeNameDialog(project)
                    if (dialog.showAndGet()) {
                        dialog.enteredName?.takeIf { it.isNotEmpty() }?.let { onNewSession(it) }
                    }
                } else {
                    val name = Messages.showInputDialog(
                        project,
                        dialogPrompt,
                        dialogTitle,
                        Messages.getQuestionIcon()
                    )?.trim().orEmpty()
                    if (name.isNotEmpty()) onNewSession(name)
                }
            }
        }
        val refreshAction = object : AnAction("Refresh", "Refresh session list", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { sessionService.refresh() }
        }
        val purgeAction = object : AnAction("Purge Old Sessions", "Delete sessions older than N days", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = PurgeDialog(project, allSessions, getStatus, sessionService)
                dialog.show()
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudeSessionsToolbar", DefaultActionGroup(newSessionAction, refreshAction, purgeAction), true)
        toolbar.targetComponent = rootPanel

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { applyFilter() }
        })

        topBar.add(toolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)

        rootPanel.add(topBar, BorderLayout.NORTH)
        if (worktreeMode) {
            setupOrphanSection()
            centerPanel.add(tableScroll, BorderLayout.CENTER)
            rootPanel.add(centerPanel, BorderLayout.CENTER)
        } else {
            rootPanel.add(tableScroll, BorderLayout.CENTER)
        }
    }

    private fun setupOrphanSection() {
        orphanList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        orphanList.visibleRowCount = 4
        orphanList.cellRenderer = OrphanCellRenderer()

        val header = com.intellij.ui.components.JBLabel("Orphaned worktrees").apply {
            border = JBUI.Borders.empty(4, 6, 2, 6)
            foreground = UIUtil.getLabelDisabledForeground()
            toolTipText = "Worktrees under .claude/worktrees/ with no Claude session. " +
                "Right-click to start a session, delete, or reveal the directory."
        }
        orphanHeader = header

        orphanPanel.add(header, BorderLayout.NORTH)
        orphanPanel.add(ScrollPaneFactory.createScrollPane(orphanList), BorderLayout.CENTER)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val orphan = orphanList.selectedValue ?: return false
                startSessionInOrphan(orphan)
                return true
            }
        }.installOn(orphanList)

        installOrphanContextMenu()
    }

    private fun installOrphanContextMenu() {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("New Session Here", "Start a Claude session in this worktree", com.clauditor.editor.ClaudeSessionIconProvider.TREE_ICON) {
                override fun actionPerformed(e: AnActionEvent) {
                    orphanList.selectedValue?.let { startSessionInOrphan(it) }
                }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = orphanList.selectedValue != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Open in File Manager", "Reveal the worktree directory", AllIcons.Actions.MenuOpen) {
                override fun actionPerformed(e: AnActionEvent) {
                    val orphan = orphanList.selectedValue ?: return
                    com.intellij.ide.actions.RevealFileAction.openDirectory(java.io.File(orphan.path))
                }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = orphanList.selectedValue != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Delete Worktree", "Remove this orphaned worktree", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    orphanList.selectedValue?.let { deleteOrphan(it) }
                }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = orphanList.selectedValue != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        PopupHandler.installPopupMenu(orphanList, group, "ClaudeOrphanWorktreeMenu")
    }

    private fun setupDoubleClick() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.selectedRow
                if (row < 0) return false
                val session = tableModel.getItem(table.convertRowIndexToModel(row))
                if (getStatus(session.sessionId) == SessionStatus.OPEN_EXTERNALLY) return false
                onSessionSelected(session)
                return true
            }
        }.installOn(table)
    }

    private fun setupContextMenu() {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Fork Session", "Create a new session branching from this one", AllIcons.Actions.SplitHorizontally) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedSession()?.let { onForkSession(it) }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSession() != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Open in Terminal", "Resume in the built-in Terminal tool window", AllIcons.Debugger.Console) {
                override fun actionPerformed(e: AnActionEvent) {
                    val session = selectedSession() ?: return
                    val log = com.intellij.openapi.diagnostic.Logger.getInstance("Clauditor.SessionListPanel")
                    try {
                        val claudePath = com.clauditor.settings.ClauditorSettings.getInstance().resolveClaudeBinary()
                        log.info("Clauditor: Open in Terminal — claude=$claudePath, session=${session.sessionId}, name=${session.displayName}")

                        val terminal = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                        val runner = org.jetbrains.plugins.terminal.LocalTerminalDirectRunner.createTerminalRunner(project)
                        val workDir = if (session.worktreeName != null && project.basePath != null) {
                            ClaudePathEncoder.worktreeAbsolutePath(project.basePath!!, session.worktreeName)
                        } else {
                            project.basePath ?: System.getProperty("user.home")
                        }
                        val claudeBin = claudePath ?: "claude"
                        log.info("Clauditor: Open in Terminal — workDir=$workDir, command=[$claudeBin, --resume, ${session.sessionId}]")

                        val tabState = org.jetbrains.plugins.terminal.TerminalTabState().apply {
                            myTabName = "claude: ${session.displayName}"
                            myWorkingDirectory = workDir
                            myShellCommand = listOf(claudeBin, "--resume", session.sessionId)
                        }
                        terminal.createNewSession(runner, tabState)
                        log.info("Clauditor: Open in Terminal — session created successfully")
                    } catch (ex: Exception) {
                        log.error("Clauditor: Open in Terminal failed — session=${session.sessionId}", ex)
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSession() != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Open Session Folder", "Reveal session files in the project directory", AllIcons.Actions.MenuOpen) {
                override fun actionPerformed(e: AnActionEvent) {
                    val basePath = project.basePath ?: return
                    val projectDir = ClaudePathEncoder.projectDir(basePath)
                    if (!Files.isDirectory(projectDir)) return
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir)
                    if (vf != null) {
                        com.intellij.ide.actions.RevealFileAction.openDirectory(projectDir.toFile())
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = project.basePath != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Copy Session ID", "Copy the session ID to clipboard", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedSession()?.let {
                        CopyPasteManager.getInstance().setContents(StringSelection(it.sessionId))
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSession() != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Delete Session", "Permanently delete this session", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    val session = selectedSession() ?: return
                    val status = getStatus(session.sessionId)
                    if (status == SessionStatus.OPEN_IN_PLUGIN || status == SessionStatus.OPEN_EXTERNALLY) {
                        Messages.showWarningDialog(project, "Close the session before deleting it.", "Cannot Delete")
                        return
                    }
                    val wt = session.worktreeName
                    // In worktree mode, if this is the only session referencing the worktree, remove
                    // the worktree too rather than leaving it orphaned.
                    val lastForWorktree = worktreeMode && wt != null &&
                        allSessions.count { it.worktreeName == wt } <= 1
                    val message = if (lastForWorktree) {
                        "Delete session \"${session.displayName}\"?\n\n" +
                            "This is the only session in worktree \"$wt\", so the worktree will also be " +
                            "removed (git worktree remove).\n\nThis cannot be undone."
                    } else {
                        "Delete session \"${session.displayName}\"?\n\nThis cannot be undone."
                    }
                    val answer = Messages.showYesNoDialog(project, message, "Delete Session", Messages.getWarningIcon())
                    if (answer == Messages.YES) {
                        sessionService.deleteSession(session.sessionId)
                        if (lastForWorktree) deleteWorktreeAfterSession(wt!!)
                    }
                }
                override fun update(e: AnActionEvent) {
                    val session = selectedSession()
                    e.presentation.isEnabled = session != null &&
                        getStatus(session.sessionId) == SessionStatus.AVAILABLE
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        PopupHandler.installPopupMenu(table, group, "ClaudeSessionsContextMenu")
    }

    private fun selectedSession(): SessionDisplay? {
        val row = table.selectedRow
        if (row < 0) return null
        return tableModel.getItem(table.convertRowIndexToModel(row))
    }

    private fun reloadData() {
        allSessions = sessionService.getSessions().filter {
            if (worktreeMode) it.worktreeName != null else it.worktreeName == null
        }
        applyFilter()
        if (worktreeMode) refreshOrphans()
    }

    private fun applyFilter() {
        val query = searchField.text.orEmpty().lowercase().trim()
        val filtered = if (query.isEmpty()) {
            allSessions
        } else {
            allSessions.filter { session ->
                session.displayName.lowercase().contains(query) ||
                    session.firstPrompt.lowercase().contains(query) ||
                    session.gitBranch?.lowercase()?.contains(query) == true ||
                    session.worktreeName?.lowercase()?.contains(query) == true
            }
        }
        tableModel.items = filtered
    }

    fun refreshStatuses() {
        ApplicationManager.getApplication().invokeLater {
            tableModel.fireTableDataChanged()
        }
    }

    /** Recompute the orphaned-worktree list off the EDT (dir scan + git). Single-flight. */
    private fun refreshOrphans() {
        val basePath = project.basePath ?: return
        if (!orphanRefreshInFlight.compareAndSet(false, true)) return
        com.clauditor.util.ClauditorExecutor.submit {
            try {
                val sessionNames = sessionService.getSessions().mapNotNull { it.worktreeName }.toSet()
                val orphans = WorktreeInspector.findOrphans(basePath, sessionNames)
                ApplicationManager.getApplication().invokeLater {
                    orphanListModel.clear()
                    orphans.forEach { orphanListModel.addElement(it) }
                    updateOrphanVisibility()
                }
            } finally {
                orphanRefreshInFlight.set(false)
            }
        }
    }

    private fun updateOrphanVisibility() {
        val n = orphanListModel.size()
        orphanHeader?.text = "Orphaned worktrees ($n)"
        if (n > 0 && orphanSplitter.parent == null) {
            centerPanel.remove(tableScroll)
            orphanSplitter.firstComponent = tableScroll
            orphanSplitter.secondComponent = orphanPanel
            centerPanel.add(orphanSplitter, BorderLayout.CENTER)
        } else if (n == 0 && orphanSplitter.parent != null) {
            centerPanel.remove(orphanSplitter)
            orphanSplitter.firstComponent = null
            orphanSplitter.secondComponent = null
            centerPanel.add(tableScroll, BorderLayout.CENTER)
        }
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    private fun startSessionInOrphan(orphan: OrphanWorktree) {
        // Optimistically drop it — creating a session makes it non-orphan; a later reload re-adds
        // it if the session never materializes.
        orphanListModel.removeElement(orphan)
        updateOrphanVisibility()
        onNewSession(orphan.name)
    }

    private fun deleteOrphan(orphan: OrphanWorktree) {
        val basePath = project.basePath ?: return
        if (orphan.registered) {
            val branchLine = orphan.branch?.let { "\nand deletes branch \"$it\" if it is fully merged." } ?: "."
            val answer = Messages.showYesNoDialog(
                project,
                "Delete worktree \"${orphan.name}\"?\n\n" +
                    "Runs git worktree remove on ${orphan.path}$branchLine\n\nThis cannot be undone.",
                "Delete Worktree",
                Messages.getWarningIcon()
            )
            if (answer == Messages.YES) runOrphanRemoval(basePath, orphan, force = false)
        } else {
            val answer = Messages.showYesNoDialog(
                project,
                "\"${orphan.name}\" is not a registered git worktree.\n\n" +
                    "Delete the directory ${orphan.path}?\n\nThis cannot be undone.",
                "Delete Directory",
                Messages.getWarningIcon()
            )
            if (answer != Messages.YES) return
            com.clauditor.util.ClauditorExecutor.submit {
                val (ok, msg) = WorktreeInspector.deleteDirectory(orphan.path)
                ApplicationManager.getApplication().invokeLater {
                    if (!ok) Messages.showErrorDialog(project, "Failed to delete directory:\n$msg", "Delete Failed")
                    refreshVfs(orphan.path)
                    refreshOrphans()
                }
            }
        }
    }

    private fun runOrphanRemoval(basePath: String, orphan: OrphanWorktree, force: Boolean) {
        com.clauditor.util.ClauditorExecutor.submit {
            val (ok, output) = WorktreeInspector.remove(basePath, orphan.path, force)
            if (!ok && !force) {
                ApplicationManager.getApplication().invokeLater {
                    val retry = Messages.showYesNoDialog(
                        project,
                        "git worktree remove failed:\n\n${output.take(400)}\n\n" +
                            "Force delete? This discards any uncommitted changes in the worktree.",
                        "Force Delete Worktree",
                        "Force Delete", "Cancel",
                        Messages.getWarningIcon()
                    )
                    if (retry == Messages.YES) runOrphanRemoval(basePath, orphan, force = true)
                }
                return@submit
            }
            if (!ok) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to remove worktree:\n${output.take(400)}", "Delete Failed")
                    refreshOrphans()
                }
                return@submit
            }
            val branchNote = orphan.branch?.let { branch ->
                val (deleted, _) = WorktreeInspector.deleteBranchIfMerged(basePath, branch)
                if (deleted) null else "Kept branch \"$branch\" — it has commits not merged into the base branch."
            }
            ApplicationManager.getApplication().invokeLater {
                if (branchNote != null) Messages.showInfoMessage(project, branchNote, "Worktree Deleted")
                refreshVfs(orphan.path)
                refreshOrphans()
            }
        }
    }

    /** Removes the worktree for [worktreeName] after its last session was deleted (no orphan left behind). */
    private fun deleteWorktreeAfterSession(worktreeName: String) {
        val basePath = project.basePath ?: return
        com.clauditor.util.ClauditorExecutor.submit {
            val entry = WorktreeInspector.list(basePath).firstOrNull {
                it.path.replace('\\', '/').removeSuffix("/").endsWith("/.claude/worktrees/$worktreeName")
            }
            val orphan = OrphanWorktree(
                name = worktreeName,
                path = entry?.path ?: ClaudePathEncoder.worktreeAbsolutePath(basePath, worktreeName),
                branch = entry?.branch,
                registered = entry != null
            )
            ApplicationManager.getApplication().invokeLater {
                if (orphan.registered) {
                    runOrphanRemoval(basePath, orphan, force = false)
                } else if (Files.exists(java.nio.file.Path.of(orphan.path))) {
                    com.clauditor.util.ClauditorExecutor.submit {
                        val (ok, msg) = WorktreeInspector.deleteDirectory(orphan.path)
                        ApplicationManager.getApplication().invokeLater {
                            if (!ok) Messages.showErrorDialog(project, "Failed to delete directory:\n$msg", "Delete Failed")
                            refreshVfs(orphan.path)
                            refreshOrphans()
                        }
                    }
                }
            }
        }
    }

    /** Tell the IDE's VFS that a directory changed on disk so the Project view / editors refresh. */
    private fun refreshVfs(path: String) {
        val parent = java.nio.file.Path.of(path).parent ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent) ?: return
        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, vf)
    }

    override fun dispose() {
        sessionService.removeChangeListener(changeListener)
    }

    private class OrphanCellRenderer : com.intellij.ui.ColoredListCellRenderer<OrphanWorktree>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out OrphanWorktree>, value: OrphanWorktree?, index: Int,
            selected: Boolean, hasFocus: Boolean
        ) {
            if (value == null) return
            icon = com.clauditor.editor.ClaudeSessionIconProvider.TREE_ICON
            append(value.name, com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            val hint = when {
                !value.registered -> "  — unregistered directory"
                value.branch != null -> "  — ${value.branch}"
                else -> "  — detached HEAD"
            }
            append(hint, com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private data class ActionMeta(
        val title: String,
        val desc: String,
        val icon: Icon,
        val prompt: String,
        val dialogTitle: String
    )
}

private class PurgeDialog(
    project: Project,
    private val sessions: List<SessionDisplay>,
    private val getStatus: (String) -> SessionStatus,
    private val sessionService: ClaudeSessionService
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val daysField = javax.swing.JTextField("30", 6)
    private val countLabel = com.intellij.ui.components.JBLabel(" ")
    private var candidateCount = 0

    init {
        title = "Purge Old Sessions"
        setOKButtonText("Delete")
        updateCount()
        daysField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { updateCount() }
        })
        init()
    }

    private fun updateCount() {
        val days = daysField.text.trim().toLongOrNull()
        if (days == null || days < 1) {
            countLabel.text = " "
            candidateCount = 0
            return
        }
        val cutoff = Instant.now().minus(days, ChronoUnit.DAYS)
        candidateCount = sessions.count {
            it.modified.isBefore(cutoff) && getStatus(it.sessionId) == SessionStatus.AVAILABLE
        }
        countLabel.text = if (candidateCount == 0) "No sessions to delete"
            else "$candidateCount session${if (candidateCount > 1) "s" else ""} will be deleted"
    }

    override fun createCenterPanel(): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(com.intellij.ui.components.JBLabel("Delete sessions not modified in the last N days:"))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(daysField)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(countLabel)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = daysField

    override fun doOKAction() {
        val days = daysField.text.trim().toLongOrNull() ?: return
        if (days < 1 || candidateCount == 0) return
        val cutoff = Instant.now().minus(days, ChronoUnit.DAYS)
        val candidates = sessions.filter {
            it.modified.isBefore(cutoff) && getStatus(it.sessionId) == SessionStatus.AVAILABLE
        }
        for (session in candidates) {
            sessionService.deleteSession(session.sessionId)
        }
        super.doOKAction()
    }
}
