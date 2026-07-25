package com.clauditor.editor

import com.clauditor.model.ClaudeStatus
import com.clauditor.services.ClaudeSessionService
import com.clauditor.services.ClaudeStatusService
import com.clauditor.services.ClaudeTerminalService
import com.clauditor.services.OpenSessionsPersistence
import com.clauditor.toolwindow.MessageHistoryPanel
import com.clauditor.toolwindow.WorktreeInspector
import com.clauditor.toolwindow.WorktreeState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.pty4j.PtyProcess
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import com.clauditor.util.RoundedProgressBarUI
import javax.swing.JProgressBar
import javax.swing.UIManager

class ClaudeSessionEditor(
    private val project: Project,
    val file: ClaudeSessionVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val log = Logger.getInstance(ClaudeSessionEditor::class.java)
    private val rootDisposable = Disposer.newDisposable("claude-editor-${file.sessionId ?: file.baseName}")
    private val loadingPanel = JBLoadingPanel(BorderLayout(), rootDisposable)
    private val loadingStopped = AtomicBoolean(false)

    /**
     * Whether this tab is actually on screen. Toolbar polling is gated on it: the periodic
     * refresh used to run for every *open* tab, so N background tabs each kept re-querying git
     * and re-reading the transcript for a toolbar nobody was looking at.
     *
     * Maintained from the EDT by a HierarchyListener and read from the poll threads.
     */
    @Volatile private var tabShowing = false
    /** Child of rootDisposable; covers everything created per PTY session so refresh() can dispose it without tearing down the editor. */
    private var sessionDisposable: Disposable = Disposer.newDisposable(rootDisposable, "claude-session")
    private var currentContent: JComponent? = null
    private var focusComponent: JComponent? = null
    private var ptyProcess: PtyProcess? = null
    private var historyPanel: MessageHistoryPanel? = null
    private var historySplitter: JBSplitter? = null
    @Volatile private var lastTitle: String? = null
    private var maxEffortButton: javax.swing.JButton? = null
    private var reconnectButton: javax.swing.JButton? = null
    private val contextProgress = JProgressBar(0, 100).apply {
        setUI(RoundedProgressBarUI())
        isStringPainted = true
        string = "Context: —"
        value = 0
        isOpaque = false
        border = JBUI.Borders.empty(5, 3)
        foreground = COLOR_GREEN
    }
    private val effortLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 6)
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val thinkingLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 6)
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val remainingLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 6)
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val costLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 6)
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val versionLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 6)
        foreground = UIManager.getColor("Label.disabledForeground")
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val version = versionLinkTarget ?: return
                com.intellij.ide.BrowserUtil.browse("https://code.claude.com/docs/en/changelog#$version")
            }
        })
    }
    private var versionLinkTarget: String? = null
    private val contextBar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 4)
        val rightPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
            effortLabel.alignmentY = 0.5f
            thinkingLabel.alignmentY = 0.5f
            remainingLabel.alignmentY = 0.5f
            costLabel.alignmentY = 0.5f
            versionLabel.alignmentY = 0.5f
            add(effortLabel)
            add(thinkingLabel)
            add(remainingLabel)
            add(costLabel)
            add(versionLabel)
        }
        add(contextProgress, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
        isVisible = false
    }

    init {
        val sessionService = ClaudeSessionService.getInstance(project)
        val persistence = OpenSessionsPersistence.getInstance(project)

        tabShowing = loadingPanel.isShowing
        loadingPanel.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                tabShowing = loadingPanel.isShowing
            }
        }

        Disposer.register(rootDisposable, Disposable { file.sessionId?.let { persistence.remove(it) } })

        // Sync tab title when session names change (e.g. after /rename)
        val nameListener: () -> Unit = {
            file.sessionId?.let { sid ->
                sessionService.getSessions().find { it.sessionId == sid }?.tabTitle?.let {
                    file.baseName = it
                    refreshTabTitle()
                }
            }
        }
        sessionService.addChangeListener(nameListener)
        Disposer.register(rootDisposable, Disposable { sessionService.removeChangeListener(nameListener) })

        // Show loading, then async-detect external status before deciding what to display
        val isResume = file.sessionId != null && file.forkFrom == null
        loadingPanel.startLoading()

        if (isResume) {
            AppExecutorUtil.getAppScheduledExecutorService().execute {
                val externalIds = com.clauditor.util.ClaudeProcessDetector
                    .detectExternalSessions(project.basePath, sessionService.getSessions())
                val isExternal = file.sessionId in externalIds
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (isExternal) {
                        file.isExternallyOpen = true
                        refreshTabTitle()
                        showExternalPanel()
                    } else {
                        startTerminalSession()
                        file.sessionId?.let { persistence.add(it) }
                    }
                }
            }
        } else {
            startTerminalSession()
        }
    }

    private fun showExternalPanel() {
        val panel = JPanel(java.awt.GridBagLayout())
        val inner = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20)
        }

        val icon = com.intellij.ui.components.JBLabel(AllIcons.General.Information).apply {
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }
        val message = com.intellij.ui.components.JBLabel("This session is open in an external terminal").apply {
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
            font = font.deriveFont(font.size2D + 2f)
            border = JBUI.Borders.emptyTop(8)
        }
        val subtitle = com.intellij.ui.components.JBLabel("Close the external session first to resume here").apply {
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
            foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
            border = JBUI.Borders.emptyTop(4)
        }
        val resumeButton = javax.swing.JButton("Resume").apply {
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
            isEnabled = false
            addActionListener {
                // Reopen the tab fresh — avoids loading panel state issues
                restartSession()
            }
        }

        inner.add(icon)
        inner.add(message)
        inner.add(subtitle)
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(16)))
        inner.add(resumeButton)

        panel.add(inner)
        currentContent = panel
        loadingPanel.add(panel, BorderLayout.CENTER)
        stopLoading()

        // Poll until the external session closes, then enable resume
        val sessionService = ClaudeSessionService.getInstance(project)
        val pollAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, sessionDisposable)
        lateinit var poll: Runnable
        poll = Runnable {
            val stillExternal = com.clauditor.util.ClaudeProcessDetector
                .detectExternalSessions(project.basePath, sessionService.getSessions())
                .contains(file.sessionId)
            if (!stillExternal) {
                ApplicationManager.getApplication().invokeLater {
                    subtitle.text = "External session closed — ready to resume"
                    resumeButton.isEnabled = true
                }
            } else if (!pollAlarm.isDisposed) {
                pollAlarm.addRequest(poll, 3000)
            }
        }
        pollAlarm.addRequest(poll, 3000)
    }

    private fun startTerminalSession() {
        val terminalService = ClaudeTerminalService.getInstance(project)
        val statusService = ClaudeStatusService.getInstance(project)
        val sessionService = ClaudeSessionService.getInstance(project)
        val persistence = OpenSessionsPersistence.getInstance(project)

        val isFork = file.forkFrom != null
        val isNewWorktree = file.newWorktreeName != null
        val isNewSession = file.sessionId == null && !isFork && !isNewWorktree
        val needsLinking = isNewSession || isFork || isNewWorktree
        val tempId = if (needsLinking) "new-${System.nanoTime()}" else null
        val monitoringId = file.sessionId ?: tempId!!
        val statusFile = statusService.createStatusFilePath(monitoringId)
        val notifyFile = statusService.createNotifyFilePath(monitoringId)

        val onActiveChanged: (Boolean) -> Unit = { active ->
            file.isThinking = active
            if (active) {
                stopLoading()
                if (file.isUnresponsive) {
                    file.isUnresponsive = false
                    reconnectButton?.let {
                        it.icon = AllIcons.Actions.Refresh
                        it.toolTipText = "Reconnect — close and resume this session"
                    }
                }
            }
            refreshTabTitle()
        }

        val onUserInput: () -> Unit = {
            if (file.notifyState != null) {
                file.notifyState = null
                statusService.clearNotifyState(file.sessionId ?: monitoringId)
                refreshTabTitle()
            }
            maxEffortButton?.let { if (!it.isEnabled) it.isEnabled = true }
        }

        val onUnresponsive: () -> Unit = {
            file.isUnresponsive = true
            reconnectButton?.let {
                it.toolTipText = "Session unresponsive — click to reconnect"
                it.icon = AllIcons.General.Error
            }
            refreshTabTitle()
        }

        val session = when {
            isFork -> terminalService.createForkWidget(file.forkFrom!!, sessionDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            file.sessionId != null -> terminalService.createResumeWidget(file.sessionId!!, sessionDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            isNewWorktree -> terminalService.createNewWorktreeWidget(file.newWorktreeName!!, sessionDisposable, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            else -> terminalService.createNewNamedSessionWidget(file.baseName, sessionDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
        }

        ptyProcess = session.process
        focusComponent = session.widget.preferredFocusableComponent

        // Normal exit (0): close the tab. Abnormal exit: keep it open so the error is visible.
        session.process.onExit().thenAccept { process ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val code = process.exitValue()
                if (code == 0) {
                    FileEditorManager.getInstance(project).closeFile(file)
                } else {
                    log.warn("Claude process exited with code $code for session ${file.sessionId}")
                    file.isThinking = false
                    file.notifyState = null
                    refreshTabTitle()
                }
            }
        }

        // Toolbar
        val toolbar = createToolbar()

        // Message history side panel
        historyPanel = MessageHistoryPanel(project, session.widget, { file.sessionId }, { file.workingDir }, sessionDisposable)
        historySplitter = JBSplitter(false, 0.8f).apply {
            firstComponent = session.widget.component
            secondComponent = null  // starts closed
        }

        // Layout: toolbar(s) on top, splitter in center, context bar at bottom
        val gitDir = file.workingDir ?: project.basePath
        // A worktree session's parent project is a git repo by definition, and its own
        // dir may not exist yet (the CLI creates it asynchronously) — so don't gate the
        // git toolbar on the dir's existence, or a brand-new worktree tab would never get
        // one. The toolbar's refresh handles the not-yet-created window itself.
        val isGitRepo = file.isWorktreeSession ||
            (gitDir != null && java.io.File(gitDir, ".git").let { it.isDirectory || it.isFile })

        val topPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(toolbar)
            // Resolve the git dir at use time: a new-worktree tab has no workingDir
            // until session linking fills it in, at which point the toolbar should
            // switch from the main repo to the worktree.
            if (isGitRepo) add(createGitToolbar { file.workingDir ?: project.basePath })
            if (file.isWorktreeSession) add(createWorktreeToolbar())
        }

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(historySplitter!!, BorderLayout.CENTER)
        contentPanel.add(contextBar, BorderLayout.SOUTH)

        currentContent = contentPanel
        loadingPanel.add(contentPanel, BorderLayout.CENTER)

        // Drag-and-drop: accept files dropped onto the terminal
        setupDropTarget(session.widget.component)

        loadingPanel.startLoading()
        AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(::stopLoading, 5L, TimeUnit.SECONDS)

        // Status monitoring
        statusService.startMonitoring(monitoringId, statusFile, notifyFile)
        Disposer.register(sessionDisposable, Disposable { statusService.stopMonitoring(monitoringId) })

        val statusListener: (String, ClaudeStatus?) -> Unit = { sid, status ->
            if (sid == monitoringId || sid == file.sessionId) {
                file.modelId = status?.modelId
                file.modelName = status?.modelName
                file.contextPercent = status?.contextRemainingPercent
                val prevNotify = file.notifyState
                val newNotify = statusService.getNotifyState(sid)
                // Suppress idle indicator on the active tab — user can already see it
                val selectedFile = FileEditorManager.getInstance(project).selectedEditor?.file
                if (newNotify == "idle_prompt" && selectedFile == file) {
                    statusService.clearNotifyState(file.sessionId ?: monitoringId)
                    file.notifyState = null
                } else {
                    file.notifyState = newNotify
                }
                refreshTabTitle()
                updateContextBar(status)

                // Notify when a non-focused tab needs attention
                if (file.notifyState != null && prevNotify == null && selectedFile != file) {
                    val msg = if (file.notifyState == "permission_prompt")
                        "${file.baseName} needs permission" else "${file.baseName} is waiting for input"
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Clauditor")
                        .createNotification(msg, NotificationType.INFORMATION)
                        .notify(project)
                }
            }
        }
        statusService.addStatusListener(statusListener)
        Disposer.register(sessionDisposable, Disposable { statusService.removeStatusListener(statusListener) })
        // Deliver cached state immediately — otherwise a re-instantiated editor
        // stays invisible until the next distinct status update arrives.
        statusService.getStatus(monitoringId)?.let { statusListener(monitoringId, it) }

        // Clear idle indicator when user switches to this tab
        val selectionListener = object : FileEditorManagerListener {
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                if (event.newFile == file && file.notifyState == "idle_prompt") {
                    file.notifyState = null
                    statusService.clearNotifyState(file.sessionId ?: monitoringId)
                    refreshTabTitle()
                }
            }

            override fun fileClosed(source: FileEditorManager, closedFile: VirtualFile) {
                if (closedFile == file) {
                    statusService.clearSession(monitoringId)
                    file.sessionId?.let { if (it != monitoringId) statusService.clearSession(it) }
                }
            }
        }
        project.messageBus.connect(sessionDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, selectionListener
        )

        // Link new/forked session to real session ID when it appears
        if (needsLinking) {
            setupNewSessionLinking(sessionService, statusService, persistence, monitoringId, statusFile, notifyFile)
        }
    }

    private fun createToolbar(): JComponent {
        val leftPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))

        leftPanel.add(createModelDropdown())

        maxEffortButton = javax.swing.JButton("max").apply {
            toolTipText = "Send /effort max"
            isFocusable = false
            addActionListener {
                sendToTerminal("/effort max\r")
                isEnabled = false
            }
        }
        leftPanel.add(maxEffortButton)

        val forkButton = javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Copy)
        forkButton.toolTipText = "Fork — open a new session branched from this one"
        forkButton.isFocusable = false
        forkButton.addActionListener {
            val sessionId = file.sessionId ?: return@addActionListener
            val forkFile = ClaudeSessionVirtualFile("Fork of ${file.baseName}", forkFrom = sessionId).apply {
                workingDir = file.workingDir
            }
            FileEditorManager.getInstance(project).openFile(forkFile, true)
        }
        leftPanel.add(forkButton)

        reconnectButton = javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Refresh).apply {
            toolTipText = "Reconnect — close and resume this session"
            isFocusable = false
            addActionListener {
                if (file.sessionId == null) return@addActionListener
                restartSession()
            }
        }
        leftPanel.add(reconnectButton)

        val deleteButton = javax.swing.JButton(AllIcons.Actions.GC)
        deleteButton.toolTipText = "Delete this session"
        deleteButton.isFocusable = false
        deleteButton.addActionListener {
            val sessionId = file.sessionId ?: return@addActionListener
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "Delete session \"${file.baseName}\"?\n\nThis cannot be undone.",
                "Delete Session",
                AllIcons.General.Warning
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return@addActionListener
            val manager = FileEditorManager.getInstance(project)
            manager.closeFile(file)
            ClaudeSessionService.getInstance(project).deleteSession(sessionId)
        }
        leftPanel.add(deleteButton)

        val rightPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 2))
        val historyToggle = javax.swing.JToggleButton("Messages")
        historyToggle.isFocusable = false
        historyToggle.addActionListener {
            val splitter = historySplitter ?: return@addActionListener
            if (historyToggle.isSelected) {
                splitter.secondComponent = historyPanel
            } else {
                splitter.secondComponent = null
            }
        }
        rightPanel.add(historyToggle)

        val toolbar = JPanel(BorderLayout())
        toolbar.add(leftPanel, BorderLayout.WEST)
        toolbar.add(rightPanel, BorderLayout.EAST)
        return toolbar
    }

    /** Path to this session's transcript JSONL — resolves the worktree project dir when applicable. */
    private fun transcriptPath(): Path? {
        val sessionId = file.sessionId ?: return null
        val basePath = project.basePath ?: return null
        val workingDir = file.workingDir
        val projectDir = if (file.isWorktreeSession && workingDir != null) {
            val wtName = Path.of(workingDir).fileName.toString()
            com.clauditor.util.ClaudePathEncoder.worktreeProjectDir(basePath, wtName)
        } else {
            com.clauditor.util.ClaudePathEncoder.projectDir(basePath)
        }
        return projectDir.resolve("$sessionId.jsonl")
    }

    private fun createGitToolbar(resolveGitDir: () -> String?): JComponent {
        val branchLabel = JBLabel(AllIcons.Vcs.Branch).apply {
            border = JBUI.Borders.empty(0, 4)
        }
        val branchName = JBLabel("").apply {
            border = JBUI.Borders.empty(0, 4)
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        val statsLabel = JBLabel("").apply {
            border = JBUI.Borders.empty(0, 4)
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        val commitButton = javax.swing.JButton("Commit").apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "No session changes to commit"
        }

        val gitRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        fun refresh() {
            val gitDir = resolveGitDir() ?: return
            // A brand-new worktree dir may not exist yet (the CLI creates it async). Until
            // its `.git` link is in place, `git -C <dir>` would walk up to the parent
            // project repo and report the wrong branch ("main"), so skip — the new-worktree
            // dir poll re-fires refresh() the moment the worktree appears.
            if (!java.io.File(gitDir, ".git").exists()) return
            if (!gitRefreshInFlight.compareAndSet(false, true)) return
            com.clauditor.util.ClauditorExecutor.submit {
                try {
                    val branch = execGit(gitDir, "branch", "--show-current").trim()
                    val status = execGit(gitDir, "status", "--porcelain").trim()
                    val changedFiles = if (status.isEmpty()) emptyList() else status.lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            val path = line.drop(3).split(" -> ").last().trim()
                            if (path.isNotEmpty()) java.nio.file.Path.of(gitDir, path).toAbsolutePath().toString() else null
                        }
                    val changed = changedFiles.size

                    // One parse per tick, shared by both consumers — this used to walk the
                    // whole transcript twice.
                    val ops = getSessionFileOperations()
                    val sessionFiles = ops.mapTo(HashSet()) { it.filePath }
                    val bySession = if (sessionFiles.isNotEmpty()) {
                        changedFiles.count { it in sessionFiles }
                    } else 0

                    val pureFiles = getPureSessionFiles(gitDir, ops)

                    ApplicationManager.getApplication().invokeLater {
                        branchName.text = branch
                        statsLabel.text = when {
                            changed == 0 -> "clean"
                            bySession > 0 -> "$changed changed ($bySession by session)"
                            else -> "$changed file${if (changed > 1) "s" else ""} changed"
                        }
                        statsLabel.foreground = if (changed > 0) COLOR_YELLOW else UIManager.getColor("Label.disabledForeground")
                        commitButton.isEnabled = pureFiles.isNotEmpty()
                        commitButton.toolTipText = when {
                            pureFiles.isNotEmpty() -> "Commit ${pureFiles.size} session file${if (pureFiles.size > 1) "s" else ""}"
                            bySession > 0 -> "Files have mixed changes from other sources"
                            else -> "No session changes to commit"
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    gitRefreshInFlight.set(false)
                }
            }
        }

        refresh()

        // Refresh on status updates (proxy for session activity)
        val statusService = ClaudeStatusService.getInstance(project)
        val listener: (String, ClaudeStatus?) -> Unit = { sid, _ ->
            if (sid == file.sessionId) refresh()
        }
        statusService.addStatusListener(listener)
        Disposer.register(sessionDisposable, Disposable { statusService.removeStatusListener(listener) })

        wireToolbarRefresh(::refresh)
        wireNewWorktreeDirPoll(::refresh)

        commitButton.addActionListener {
            val gitDir = resolveGitDir() ?: return@addActionListener
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val pureFiles = getPureSessionFiles(gitDir, getSessionFileOperations())
                    if (pureFiles.isEmpty()) return@executeOnPooledThread

                    // Stage only the session's pure files
                    for (f in pureFiles) {
                        val rel = java.nio.file.Path.of(gitDir).relativize(java.nio.file.Path.of(f)).toString()
                        execGitWithExitCode(gitDir, "add", rel)
                    }

                    // Ask Claude to generate the commit message and commit
                    val fileList = pureFiles.joinToString(", ") {
                        java.nio.file.Path.of(gitDir).relativize(java.nio.file.Path.of(it)).toString()
                    }
                    ApplicationManager.getApplication().invokeLater {
                        sendToTerminal("commit the staged changes to: $fileList\r")
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Commit failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        }

        branchLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        branchName.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        statsLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT

        val leftPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(branchLabel)
            add(branchName)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(statsLabel)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(commitButton)
        }

        val bar = JPanel(BorderLayout())
        bar.add(leftPanel, BorderLayout.WEST)
        return bar
    }

    private fun createWorktreeToolbar(): JComponent {
        // file.workingDir is read at use time, not captured: a tab born from
        // "New worktree session" has no workingDir until session linking fills it in.
        val projectPath = project.basePath

        val branchLabel = JBLabel(ClaudeSessionIconProvider.TREE_ICON).apply {
            border = JBUI.Borders.empty(0, 4)
        }
        val statusLabel = JBLabel("").apply {
            border = JBUI.Borders.empty(0, 4)
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        // Track current branch names and ahead/behind for button state
        var currentWtBranch = ""
        var currentMainBranch = ""
        var currentAhead = 0
        var currentBehind = 0
        var hasUncommitted = false

        val commitButton = javax.swing.JButton("Commit").apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "No uncommitted changes"
        }
        val updateButton = javax.swing.JButton("\u2193 Update").apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "Up to date"
        }
        val mergeButton = javax.swing.JButton("\u2191 Merge to project").apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "Nothing to merge"
        }

        val openInIdeButton = javax.swing.JButton("Open in IDE").apply {
            isFocusable = false
            addActionListener {
                file.workingDir?.let { wt ->
                    com.intellij.ide.impl.ProjectUtil.openOrImport(java.nio.file.Path.of(wt), null, true)
                }
            }
        }

        val openInTerminalButton = javax.swing.JButton("Open in Terminal").apply {
            isFocusable = false
            addActionListener {
                val worktreePath = file.workingDir ?: return@addActionListener
                try {
                    val terminal = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                    val runner = org.jetbrains.plugins.terminal.LocalTerminalDirectRunner.createTerminalRunner(project)
                    val tabState = org.jetbrains.plugins.terminal.TerminalTabState().apply {
                        myTabName = worktreePath.substringAfterLast('/')
                        myWorkingDirectory = worktreePath
                    }
                    terminal.createNewSession(runner, tabState)
                } catch (ex: Exception) {
                    log.warn("Failed to open terminal at $worktreePath", ex)
                    showNotification("Failed to open terminal: ${ex.message}", NotificationType.ERROR)
                }
            }
        }

        val revealButton = javax.swing.JButton(AllIcons.Actions.MenuOpen).apply {
            isFocusable = false
            addActionListener {
                file.workingDir?.let { wt ->
                    com.intellij.ide.actions.RevealFileAction.openDirectory(java.io.File(wt))
                }
            }
        }

        val showChangesButton = javax.swing.JButton("Show Changes").apply {
            isFocusable = false
            addActionListener {
                val wt = file.workingDir ?: return@addActionListener
                com.clauditor.util.ClauditorExecutor.submit {
                    val result = com.clauditor.toolwindow.WorktreeChanges.compute(wt)
                    ApplicationManager.getApplication().invokeLater {
                        if (result.changes.isEmpty()) {
                            showNotification(result.error ?: "No changes in this worktree.", NotificationType.INFORMATION)
                        } else {
                            com.clauditor.toolwindow.WorktreeChanges.openDialog(
                                project, "Changes — ${wt.substringAfterLast('/')}", wt, result.changes
                            )
                        }
                    }
                }
            }
        }

        // These actions need the worktree path, which a brand-new worktree session
        // doesn't have until the first message links it. Disabled until then.
        fun updateOpenButtons() {
            val hasPath = file.workingDir != null
            val noPathTip = "Available after the first message — the worktree hasn't been created yet"
            openInIdeButton.isEnabled = hasPath
            openInIdeButton.toolTipText = if (hasPath) "Open worktree directory as a separate project" else noPathTip
            openInTerminalButton.isEnabled = hasPath
            openInTerminalButton.toolTipText = if (hasPath) "Open a terminal tab in this IDE rooted at the worktree directory" else noPathTip
            revealButton.isEnabled = hasPath
            revealButton.toolTipText = if (hasPath) "Reveal worktree directory in file manager" else noPathTip
            showChangesButton.isEnabled = hasPath
            showChangesButton.toolTipText = if (hasPath) "Show this worktree's diff (committed vs base + uncommitted)" else noPathTip
        }
        updateOpenButtons()

        val wtRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        fun refreshBranchStatus() {
            ApplicationManager.getApplication().invokeLater { updateOpenButtons() }
            val worktreePath = file.workingDir
            if (worktreePath == null || projectPath == null) return
            // Brand-new worktree the CLI hasn't created yet (dir absent, session not linked):
            // show a neutral "creating…" pending state rather than a red "missing", and don't
            // run git against a nonexistent dir. The new-worktree dir poll re-fires this until
            // the worktree appears, at which point the normal path renders the real branch.
            if (file.sessionId == null && !java.nio.file.Files.isDirectory(java.nio.file.Path.of(worktreePath))) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = worktreePath.substringAfterLast('/') + "  — creating…"
                    statusLabel.foreground = UIManager.getColor("Label.disabledForeground")
                    statusLabel.toolTipText = "Setting up the worktree…"
                }
                return
            }
            if (!wtRefreshInFlight.compareAndSet(false, true)) return
            com.clauditor.util.ClauditorExecutor.submit {
                val info = try {
                    WorktreeInspector.status(worktreePath, projectPath)
                } finally {
                    wtRefreshInFlight.set(false)
                }
                ApplicationManager.getApplication().invokeLater {
                    info.wtBranch?.let { currentWtBranch = it }
                    info.mainBranch?.let { currentMainBranch = it }
                    currentAhead = info.ahead
                    currentBehind = info.behind
                    hasUncommitted = info.dirty

                    statusLabel.text = buildString {
                        append(info.name)
                        when (info.state) {
                            WorktreeState.REBASING -> append("  \u2014 REBASING")
                            WorktreeState.MERGING -> append("  \u2014 MERGING")
                            WorktreeState.CHERRY_PICKING -> append("  \u2014 CHERRY-PICKING")
                            WorktreeState.DETACHED -> append("  \u2014 detached HEAD")
                            WorktreeState.MISSING -> append("  \u2014 missing")
                            WorktreeState.UNKNOWN -> append("  \u2014 status unavailable")
                            WorktreeState.OK -> {
                                if (info.ahead > 0 || info.behind > 0) {
                                    append("  ")
                                    if (info.ahead > 0) append("\u2191${info.ahead}")
                                    if (info.ahead > 0 && info.behind > 0) append(" ")
                                    if (info.behind > 0) append("\u2193${info.behind}")
                                    info.mainBranch?.let { append(" vs $it") }
                                }
                            }
                        }
                    }
                    statusLabel.foreground = if (info.state == WorktreeState.OK)
                        UIManager.getColor("Label.disabledForeground") else JBColor.RED
                    statusLabel.toolTipText = when (info.state) {
                        WorktreeState.UNKNOWN -> "Status unavailable: ${info.errorDetail ?: "unknown error"}"
                        WorktreeState.MISSING -> info.errorDetail
                        else -> null
                    }

                    if (info.state != WorktreeState.OK) {
                        val frozenTip = when (info.state) {
                            WorktreeState.REBASING -> "Rebase in progress \u2014 resolve in the terminal with git rebase --continue or --abort"
                            WorktreeState.MERGING -> "Merge in progress \u2014 resolve in the terminal with git merge --continue or --abort"
                            WorktreeState.CHERRY_PICKING -> "Cherry-pick in progress \u2014 resolve in the terminal with git cherry-pick --continue or --abort"
                            WorktreeState.DETACHED -> "Detached HEAD \u2014 check out a branch first"
                            WorktreeState.MISSING -> "Worktree directory not found"
                            WorktreeState.UNKNOWN -> "Status unavailable: ${info.errorDetail ?: "unknown error"}"
                            WorktreeState.OK -> ""
                        }
                        listOf(commitButton, updateButton, mergeButton).forEach {
                            it.isEnabled = false
                            it.toolTipText = frozenTip
                        }
                    } else {
                        val wtBranch = info.wtBranch ?: ""
                        val mainBranch = info.mainBranch ?: ""

                        updateButton.isEnabled = info.behind > 0 && !info.dirty
                        updateButton.toolTipText = when {
                            info.dirty -> "Commit or stash worktree changes first"
                            info.behind > 0 -> "Rebase $wtBranch onto ${info.behind} new commit${if (info.behind > 1) "s" else ""} from $mainBranch"
                            else -> "Up to date with $mainBranch"
                        }

                        mergeButton.isEnabled = info.ahead > 0 && info.behind == 0
                        mergeButton.toolTipText = when {
                            info.ahead == 0 -> "Nothing to merge"
                            info.behind > 0 -> "Update worktree first \u2014 $mainBranch has diverged"
                            else -> "Fast-forward ${info.ahead} commit${if (info.ahead > 1) "s" else ""} into $mainBranch"
                        }

                        commitButton.isEnabled = info.dirty
                        commitButton.toolTipText = if (info.dirty) "Ask Claude to commit changes" else "No uncommitted changes"
                    }
                }
            }
        }

        commitButton.addActionListener {
            commitButton.isEnabled = false
            sendToTerminal("commit all changes with a descriptive commit message\r")
        }

        updateButton.addActionListener {
            val worktreePath = file.workingDir ?: return@addActionListener
            updateButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val result = execGitWithExitCode(worktreePath, "rebase", currentMainBranch)
                    ApplicationManager.getApplication().invokeLater {
                        if (result.exitCode == 0) {
                            showNotification("Rebased $currentWtBranch onto $currentMainBranch", NotificationType.INFORMATION)
                            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh {}
                        } else if (result.output.contains("CONFLICT")) {
                            showNotification(
                                "Rebase conflicts \u2014 resolve in the terminal with git rebase --continue or --abort",
                                NotificationType.WARNING
                            )
                        } else {
                            showNotification("Rebase failed: ${result.output.take(200)}", NotificationType.ERROR)
                        }
                    }
                    refreshBranchStatus()
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Rebase failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        }

        mergeButton.addActionListener {
            if (projectPath == null) return@addActionListener
            mergeButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val result = execGitWithExitCode(projectPath, "merge", "--ff-only", currentWtBranch)
                    ApplicationManager.getApplication().invokeLater {
                        if (result.exitCode == 0) {
                            showNotification("Merged $currentWtBranch into $currentMainBranch (fast-forward)", NotificationType.INFORMATION)
                            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh {}
                        } else {
                            showNotification(
                                "Fast-forward not possible \u2014 update worktree first",
                                NotificationType.WARNING
                            )
                        }
                    }
                    refreshBranchStatus()
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Merge failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        }

        refreshBranchStatus()

        // Refresh when session status changes (proxy for activity)
        val statusService = ClaudeStatusService.getInstance(project)
        val listener: (String, com.clauditor.model.ClaudeStatus?) -> Unit = { sid, _ ->
            if (sid == file.sessionId) refreshBranchStatus()
        }
        statusService.addStatusListener(listener)
        Disposer.register(sessionDisposable, Disposable { statusService.removeStatusListener(listener) })

        wireToolbarRefresh(::refreshBranchStatus)
        wireNewWorktreeDirPoll(::refreshBranchStatus)

        branchLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        statusLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        commitButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        updateButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        mergeButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT

        val leftPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(branchLabel)
            add(statusLabel)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(commitButton)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(updateButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(mergeButton)
        }

        showChangesButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        openInIdeButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        openInTerminalButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        revealButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT

        val rightPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(showChangesButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(openInIdeButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(openInTerminalButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(revealButton)
        }

        val bar = JPanel(BorderLayout())
        bar.add(leftPanel, BorderLayout.WEST)
        bar.add(rightPanel, BorderLayout.EAST)
        return bar
    }

    /**
     * Wire periodic + tab-focus refresh for a toolbar's status callback.
     * The ClaudeStatusService listener already covers the active case (status changes
     * while Claude is working); this fills the gap when Claude is idle but external
     * state (commits in the terminal, remote pulls, finished rebases) can drift.
     */
    private fun wireToolbarRefresh(refresh: () -> Unit) {
        val refreshAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, sessionDisposable)
        lateinit var tick: Runnable
        tick = Runnable {
            // Only the visible tab polls. A background tab's toolbar is repainted by the
            // selection listener below the moment it comes to the front, so nothing is stale
            // by the time anyone can see it.
            if (tabShowing) refresh()
            val sec = com.clauditor.settings.ClauditorSettings.getInstance().state.branchStatusRefreshSeconds
            if (sec > 0 && !refreshAlarm.isDisposed) {
                refreshAlarm.addRequest(tick, sec * 1000)
            }
        }
        val initialSec = com.clauditor.settings.ClauditorSettings.getInstance().state.branchStatusRefreshSeconds
        if (initialSec > 0) {
            refreshAlarm.addRequest(tick, initialSec * 1000)
        }

        val selectionListener = object : FileEditorManagerListener {
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                if (event.newFile == file) refresh()
            }
        }
        project.messageBus.connect(sessionDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, selectionListener
        )
    }

    /**
     * A brand-new worktree tab knows its directory up front (set eagerly at creation), but
     * the CLI creates that directory asynchronously a moment after spawn. During that window
     * nothing else refreshes the toolbars: pre-link status events are dropped (they key on
     * sessionId, still null), and the periodic branch-status poll may be disabled. This
     * bridges the gap with a short bounded poll that re-runs [refresh] until the worktree
     * dir exists or the session links — then the normal triggers take over.
     *
     * No-ops for anything that isn't a not-yet-created new worktree (no workingDir, dir
     * already present, or already linked), so it's safe to wire from every toolbar.
     */
    private fun wireNewWorktreeDirPoll(refresh: () -> Unit) {
        val worktreeDir = file.workingDir ?: return
        if (file.sessionId != null) return
        if (java.io.File(worktreeDir).isDirectory) return

        val alarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, sessionDisposable)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var tick: Runnable
        tick = Runnable {
            refresh()
            // Stop once the worktree exists (refresh above just rendered it), once linking
            // wired the normal triggers, or after a backstop cap (~30s) so an abandoned
            // creation doesn't poll forever.
            val done = java.io.File(worktreeDir).isDirectory ||
                file.sessionId != null ||
                attempts.incrementAndGet() >= 40
            if (!done && !alarm.isDisposed) {
                alarm.addRequest(tick, 750)
            }
        }
        if (!alarm.isDisposed) alarm.addRequest(tick, 750)
    }

    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Clauditor")
            .createNotification(message, type)
            .notify(project)
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun execGit(workDir: String, vararg args: String): String {
        return execGitWithExitCode(workDir, *args).output
    }

    private fun execGitWithExitCode(workDir: String, vararg args: String): GitResult {
        val res = com.clauditor.util.ProcessHelper.execWithTimeout(
            command = arrayOf("git", "-C", workDir, *args),
            timeoutMs = 15_000,
            extraEnv = mapOf("GIT_OPTIONAL_LOCKS" to "0")
        )
        return GitResult(res.exitCode, res.output)
    }

    private data class SessionFileOp(
        val filePath: String,
        val type: String,           // "Write" or "Edit"
        val content: String? = null, // Write: full file content
        val oldString: String? = null, // Edit
        val newString: String? = null  // Edit
    )

    /** Parse the JSONL for Write/Edit tool uses with full operation details. */
    // Incremental parse state for this session's Write/Edit operations. Only the ops are
    // retained, which measured ~2% of transcript bytes (0.86 MB for a 44 MB session) — cheap
    // enough to keep, and it saves re-reading the whole transcript on every toolbar tick.
    private val opsTail = com.clauditor.util.JsonlTailReader()
    private val cachedOps = mutableListOf<SessionFileOp>()
    private var cachedOpsPath: java.nio.file.Path? = null

    /**
     * The session's file-writing operations, oldest first.
     *
     * Parses only what the transcript has gained since the last call; the op list is
     * append-only, so accumulating it is equivalent to re-reading from the top.
     */
    @Synchronized
    private fun getSessionFileOperations(): List<SessionFileOp> {
        val jsonlPath = transcriptPath() ?: return emptyList()
        if (!java.nio.file.Files.exists(jsonlPath)) return emptyList()

        // Session linked or rebound to a different transcript: start over.
        if (jsonlPath != cachedOpsPath) {
            cachedOpsPath = jsonlPath
            opsTail.reset()
            cachedOps.clear()
        }

        try {
            opsTail.consume(jsonlPath, onRestart = { cachedOps.clear() }) { line ->
                appendFileOps(line, cachedOps)
            }
        } catch (_: Exception) {}
        return java.util.ArrayList(cachedOps)
    }

    /** Extract any Write/Edit tool uses on one transcript line into [into]. */
    private fun appendFileOps(line: String, into: MutableList<SessionFileOp>) {
        try {
            val obj = com.google.gson.Gson().fromJson(line, com.google.gson.JsonObject::class.java)
            if (obj.get("type")?.asString != "assistant") return
            val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
            for (block in content) {
                if (!block.isJsonObject) continue
                val b = block.asJsonObject
                if (b.get("type")?.asString != "tool_use") continue
                val name = b.get("name")?.asString ?: continue
                val input = b.getAsJsonObject("input") ?: continue
                val filePath = input.get("file_path")?.asString ?: continue
                when (name) {
                    "Write" -> into.add(SessionFileOp(filePath, "Write",
                        content = input.get("content")?.asString))
                    "Edit" -> into.add(SessionFileOp(filePath, "Edit",
                        oldString = input.get("old_string")?.asString,
                        newString = input.get("new_string")?.asString))
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Check if the session's changed files are "pure" — i.e. the only changes in each
     * file are from this session's Write/Edit operations.
     *
     * Approach: for each session-touched file that has uncommitted changes, replay the
     * session's operations against the HEAD version. If the result matches the current
     * file content, no external changes are mixed in.
     *
     * Returns the list of pure session files ready to commit, or empty if impure/nothing to commit.
     */
    private fun getPureSessionFiles(gitDir: String, ops: List<SessionFileOp>): List<String> {
        if (ops.isEmpty()) return emptyList()

        val status = execGit(gitDir, "status", "--porcelain").trim()
        if (status.isEmpty()) return emptyList()

        val changedFiles = status.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val path = line.drop(3).split(" -> ").last().trim()
            if (path.isNotEmpty()) java.nio.file.Path.of(gitDir, path).toAbsolutePath().toString() else null
        }.toSet()

        val sessionFiles = ops.map { it.filePath }.toSet()
        val sessionChangedFiles = changedFiles.filter { it in sessionFiles }
        if (sessionChangedFiles.isEmpty()) return emptyList()

        val pureFiles = mutableListOf<String>()
        for (filePath in sessionChangedFiles) {
            val fileOps = ops.filter { it.filePath == filePath }
            if (isFilePure(gitDir, filePath, fileOps)) {
                pureFiles.add(filePath)
            } else {
                return emptyList() // any impure file means we can't safely commit
            }
        }
        return pureFiles
    }

    private fun isFilePure(gitDir: String, filePath: String, ops: List<SessionFileOp>): Boolean {
        val relativePath = java.nio.file.Path.of(gitDir).relativize(java.nio.file.Path.of(filePath)).toString()

        // Get HEAD version (empty if file is new)
        val headContent = try {
            val result = execGitWithExitCode(gitDir, "show", "HEAD:$relativePath")
            if (result.exitCode == 0) result.output else ""
        } catch (_: Exception) { "" }

        // Replay session operations in order
        var simulated = headContent
        for (op in ops) {
            when (op.type) {
                "Write" -> simulated = op.content ?: return false
                "Edit" -> {
                    val old = op.oldString ?: return false
                    val new = op.newString ?: return false
                    if (!simulated.contains(old)) return false
                    simulated = simulated.replaceFirst(old, new)
                }
            }
        }

        // Compare simulation with current file
        val currentContent = try {
            java.nio.file.Files.readString(java.nio.file.Path.of(filePath))
        } catch (_: Exception) { return false }

        return simulated == currentContent
    }

    private fun createModelDropdown(): JComponent {
        val aliases = arrayOf("sonnet", "opus", "haiku", "sonnet[1m]", "opus[1m]", "opusplan")

        // Map model IDs from the status line to the alias used in /model
        fun idToAlias(modelId: String): String? {
            val id = modelId.lowercase()
            val has1m = id.contains("[1m]")
            return when {
                id.contains("opus") -> if (has1m) "opus[1m]" else "opus"
                id.contains("sonnet") -> if (has1m) "sonnet[1m]" else "sonnet"
                id.contains("haiku") -> "haiku"
                else -> null
            }
        }

        val combo = javax.swing.JComboBox(aliases)
        combo.isFocusable = false
        var updating = false

        // Set initial selection from current status
        file.modelId?.let { idToAlias(it) }?.let { combo.selectedItem = it }

        combo.addActionListener {
            if (updating) return@addActionListener
            val selected = combo.selectedItem as? String ?: return@addActionListener
            sendToTerminal("/model $selected\r")
        }

        // Sync selection when status updates report a different model
        val statusService = ClaudeStatusService.getInstance(project)
        val listener: (String, ClaudeStatus?) -> Unit = listener@{ sid, status ->
            val monitoringId = file.sessionId ?: return@listener
            if (sid != monitoringId) return@listener
            val matched = status?.modelId?.let { idToAlias(it) } ?: return@listener
            ApplicationManager.getApplication().invokeLater {
                if (combo.selectedItem != matched) {
                    updating = true
                    combo.selectedItem = matched
                    updating = false
                }
            }
        }
        statusService.addStatusListener(listener)
        Disposer.register(sessionDisposable, Disposable { statusService.removeStatusListener(listener) })

        return combo
    }

    private fun setupDropTarget(component: JComponent) {
        val highlightColor = JBUI.CurrentTheme.Focus.focusColor()
        val highlightBorder = BorderFactory.createLineBorder(highlightColor, 2)
        val originalBorder = component.border

        DropTarget(component, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    component.border = highlightBorder
                    component.repaint()
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragExit(dte: DropTargetEvent) {
                component.border = originalBorder
                component.repaint()
            }

            override fun drop(dtde: DropTargetDropEvent) {
                component.border = originalBorder
                component.repaint()

                if (!dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.rejectDrop()
                    return
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val files = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val paths = files.map { it.absolutePath }
                    if (paths.isNotEmpty()) {
                        sendToTerminal(paths.joinToString(" "))
                    }
                    dtde.dropComplete(true)
                } catch (e: Exception) {
                    log.warn("Failed to handle file drop", e)
                    dtde.dropComplete(false)
                }
            }
        })
    }

    fun sendToTerminal(text: String) {
        val process = ptyProcess ?: return
        if (!process.isAlive) return
        try {
            process.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        } catch (e: Exception) {
            log.warn("Failed to send input to terminal", e)
        }
    }

    private fun setupNewSessionLinking(
        sessionService: ClaudeSessionService,
        statusService: ClaudeStatusService,
        persistence: OpenSessionsPersistence,
        tempMonitoringId: String,
        tempStatusFile: Path,
        notifyFile: Path
    ) {
        val existingIds = sessionService.getSessions().map { it.sessionId }.toSet()
        val startedAt = System.currentTimeMillis()

        lateinit var linkListener: () -> Unit
        linkListener = {
            val openIds = FileEditorManager.getInstance(project).openFiles
                .filterIsInstance<ClaudeSessionVirtualFile>()
                .mapNotNull { it.sessionId }
                .toSet()

            val candidate = sessionService.getSessions()
                .filter { it.sessionId !in existingIds && it.sessionId !in openIds }
                .minByOrNull { kotlin.math.abs(it.modified.toEpochMilli() - startedAt) }

            if (candidate != null) {
                file.sessionId = candidate.sessionId
                file.baseName = candidate.tabTitle
                if (file.workingDir == null && candidate.worktreeName != null && project.basePath != null) {
                    file.workingDir = com.clauditor.util.ClaudePathEncoder
                        .worktreeAbsolutePath(project.basePath!!, candidate.worktreeName)
                }
                persistence.add(candidate.sessionId)

                // Migrate status monitoring from temp to real session ID.
                // Keep the original temp file paths — CLAUDITOR_STATUS_FILE/NOTIFY_FILE
                // were baked into the process env at spawn, so claude keeps writing to
                // the temp paths for the life of the process.
                statusService.stopMonitoring(tempMonitoringId)
                statusService.startMonitoring(candidate.sessionId, tempStatusFile, notifyFile)

                // Force: a worktree tab's title doesn't change on link (still the
                // worktree name), but the tab tooltip now has a session ID to show.
                refreshTabTitle(force = true)
                sessionService.removeChangeListener(linkListener)
            }
        }
        sessionService.addChangeListener(linkListener)
        Disposer.register(sessionDisposable, Disposable { sessionService.removeChangeListener(linkListener) })
    }

    private fun stopLoading() {
        if (loadingStopped.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater { loadingPanel.stopLoading() }
        }
    }

    private fun updateContextBar(status: ClaudeStatus?) {
        ApplicationManager.getApplication().invokeLater {
            val usedPercent = status?.contextUsedPercent
            if (usedPercent == null) {
                // Don't hide the bar if it was previously shown — keep last known values
                return@invokeLater
            }
            val pct = usedPercent.toInt().coerceIn(0, 100)
            contextProgress.value = pct
            contextProgress.string = "Context: $pct%"
            contextProgress.foreground = when {
                usedPercent < 50 -> COLOR_GREEN
                usedPercent < 80 -> COLOR_YELLOW
                else -> COLOR_RED
            }
            effortLabel.text = status.effortLevel?.let { "effort: $it" } ?: ""
            thinkingLabel.text = if (status.thinkingEnabled == true) "thinking" else ""
            remainingLabel.text = when {
                status.contextRemainingTokens != null && status.contextWindowSize != null ->
                    "${formatTokens(status.contextRemainingTokens)} / ${formatTokens(status.contextWindowSize)}"
                status.contextRemainingTokens != null ->
                    "${formatTokens(status.contextRemainingTokens)} left"
                else -> ""
            }
            costLabel.text = status.costUsd?.let { String.format("$%.2f", it) } ?: ""
            status.cliVersion?.let { running ->
                versionLinkTarget = running.replace('.', '-')
                val statusService = ClaudeStatusService.getInstance(project)
                statusService.refreshVersions()
                val installed = statusService.getInstalledCliVersion()
                val published = statusService.getPublishedCliVersion()

                val hasPendingUpgrade = installed != null && isNewerVersion(installed, running)
                val hasNewerPublished = published != null
                        && isNewerVersion(published, running)
                        && (installed == null || isNewerVersion(published, installed))

                val starSuffix = if (hasNewerPublished) " *" else ""
                versionLabel.text = if (hasPendingUpgrade) {
                    "v$running (update: v$installed)$starSuffix"
                } else {
                    "v$running$starSuffix"
                }
                versionLabel.foreground = if (hasPendingUpgrade) COLOR_YELLOW
                    else UIManager.getColor("Label.disabledForeground")

                val baseTip = "View release notes for v$running"
                versionLabel.toolTipText =
                    if (hasNewerPublished) "$baseTip — v$published published" else baseTip

                reconnectButton?.let {
                    if (hasPendingUpgrade) {
                        it.icon = com.intellij.icons.AllIcons.Actions.ForceRefresh
                        it.toolTipText = "Reconnect — update available (v$running \u2192 v$installed)"
                    } else {
                        it.icon = com.intellij.icons.AllIcons.Actions.Refresh
                        it.toolTipText = "Reconnect — close and resume this session"
                    }
                }
            }
            contextBar.isVisible = true
        }
    }

    /**
     * Tear down the PTY and all session-scoped state, then re-run startTerminalSession()
     * inside the same FileEditor. Keeping the editor instance means the tab stays put —
     * we don't have to close+reopen the file (which would append the tab to the end of
     * the strip).
     */
    private fun restartSession() {
        try {
            ptyProcess?.destroyForcibly()
        } catch (_: Exception) {}
        ptyProcess = null

        Disposer.dispose(sessionDisposable)
        sessionDisposable = Disposer.newDisposable(rootDisposable, "claude-session")

        focusComponent = null
        historyPanel = null
        historySplitter = null
        maxEffortButton = null
        lastTitle = null

        // Reset class-level UI components so they don't show stale state until the new PTY reports back.
        // (reconnectButton is rebuilt fresh inside createToolbar — nothing to reset on it here.)
        contextBar.isVisible = false
        contextProgress.value = 0
        contextProgress.string = "Context: —"
        effortLabel.text = ""
        thinkingLabel.text = ""
        remainingLabel.text = ""
        costLabel.text = ""
        versionLabel.text = ""
        versionLinkTarget = null

        // JBLoadingPanel delegates add() into its inner contentPanel but inherits
        // Container.remove(), so removing via loadingPanel itself is a silent no-op —
        // the dead session's UI would stay stacked above the new one, eating clicks.
        currentContent?.let { loadingPanel.contentPanel.remove(it) }
        currentContent = null

        file.isThinking = false
        file.notifyState = null
        file.isUnresponsive = false
        file.isExternallyOpen = false
        refreshTabTitle()

        loadingStopped.set(false)
        loadingPanel.startLoading()
        loadingPanel.revalidate()
        loadingPanel.repaint()

        startTerminalSession()
        // Cover the external→resume path: showExternalPanel skipped persistence.add, but now we have a live PTY.
        file.sessionId?.let { OpenSessionsPersistence.getInstance(project).add(it) }

        // Nothing re-queries getPreferredFocusedComponent on an in-place swap, so
        // without this the prompt isn't typeable until the user switches tabs.
        focusComponent?.let { fc ->
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(fc, true)
            }
        }
    }

    private fun refreshTabTitle(force: Boolean = false) {
        // The status glyph now lives in the tab icon, not the title text, so the name alone
        // no longer changes when Claude's state cycles. Key the dedup on glyph + name so a
        // pure status change still triggers updateFilePresentation (which refreshes the icon).
        val signature = "${file.statusGlyph()} ${file.computeTabTitle()}"
        if (!force && signature == lastTitle) return
        lastTitle = signature
        ApplicationManager.getApplication().invokeLater {
            (FileEditorManager.getInstance(project) as? FileEditorManagerEx)
                ?.updateFilePresentation(file)
        }
    }

    override fun getComponent(): JComponent = loadingPanel
    override fun getPreferredFocusedComponent(): JComponent? = focusComponent
    override fun getName(): String = file.computeTabTitle()
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() { Disposer.dispose(rootDisposable) }

    companion object {
        private val COLOR_GREEN = Color(0x5B, 0xA8, 0x5B)
        private val COLOR_YELLOW = Color(0xD4, 0xA0, 0x1E)
        private val COLOR_RED = Color(0xD4, 0x4B, 0x4B)

        private fun formatTokens(tokens: Long): String = when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.0fk", tokens / 1_000.0)
            else -> tokens.toString()
        }

        private fun isNewerVersion(a: String, b: String): Boolean {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val ai = pa.getOrElse(i) { 0 }
                val bi = pb.getOrElse(i) { 0 }
                if (ai != bi) return ai > bi
            }
            return false
        }
    }
}
