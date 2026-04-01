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

        val cm = table.columnModel
        if (worktreeMode) {
            cm.getColumn(1).preferredWidth = 140  // Name
            cm.getColumn(2).preferredWidth = 100  // Worktree
            cm.getColumn(3).preferredWidth = 400  // First Prompt
            cm.getColumn(4).preferredWidth = 50   // Msgs
        } else {
            cm.getColumn(1).preferredWidth = 140  // Name
            cm.getColumn(2).preferredWidth = 400  // First Prompt
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
                val name = Messages.showInputDialog(
                    project,
                    dialogPrompt,
                    dialogTitle,
                    Messages.getQuestionIcon()
                )?.trim().orEmpty()
                if (name.isNotEmpty()) onNewSession(name)
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
        rootPanel.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
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
                    val terminal = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                    val runner = org.jetbrains.plugins.terminal.LocalTerminalDirectRunner.createTerminalRunner(project)
                    val workDir = if (session.worktreeName != null && project.basePath != null) {
                        ClaudePathEncoder.worktreeAbsolutePath(project.basePath!!, session.worktreeName)
                    } else {
                        project.basePath ?: System.getProperty("user.home")
                    }
                    val tabState = org.jetbrains.plugins.terminal.TerminalTabState().apply {
                        myTabName = "claude: ${session.displayName}"
                        myWorkingDirectory = workDir
                        myShellCommand = listOf("claude", "--resume", session.sessionId)
                    }
                    terminal.createNewSession(runner, tabState)
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
                    val answer = Messages.showYesNoDialog(
                        project,
                        "Delete session \"${session.displayName}\"?\n\nThis cannot be undone.",
                        "Delete Session",
                        Messages.getWarningIcon()
                    )
                    if (answer == Messages.YES) {
                        sessionService.deleteSession(session.sessionId)
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
    }

    private fun applyFilter() {
        val query = searchField.text.orEmpty().lowercase().trim()
        val filtered = if (query.isEmpty()) {
            allSessions
        } else {
            allSessions.filter { session ->
                session.displayName.lowercase().contains(query) ||
                    session.firstPrompt.lowercase().contains(query) ||
                    session.summary?.lowercase()?.contains(query) == true ||
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

    override fun dispose() {
        sessionService.removeChangeListener(changeListener)
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
