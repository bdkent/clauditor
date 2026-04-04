package com.clauditor.editor

import com.clauditor.model.ClaudeStatus
import com.clauditor.services.ClaudeSessionService
import com.clauditor.services.ClaudeStatusService
import com.clauditor.services.ClaudeTerminalService
import com.clauditor.services.OpenSessionsPersistence
import com.clauditor.toolwindow.MessageHistoryPanel
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
import java.nio.file.StandardCopyOption
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
    private var focusComponent: JComponent? = null
    private var ptyProcess: PtyProcess? = null
    private var historyPanel: MessageHistoryPanel? = null
    private var historySplitter: JBSplitter? = null
    @Volatile private var lastTitle: String? = null
    private val transientCache = mutableMapOf<String, Pair<Long, String>>() // key -> (mtime, result)
    private var summarizeButton: javax.swing.JButton? = null
    private val reconnectButton = javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Refresh).apply {
        toolTipText = "Reconnect — close and resume this session"
        isFocusable = false
    }
    private val contextProgress = JProgressBar(0, 100).apply {
        setUI(RoundedProgressBarUI())
        isStringPainted = true
        string = "Context: —"
        value = 0
        isOpaque = false
        border = JBUI.Borders.empty(5, 3)
        foreground = COLOR_GREEN
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
            remainingLabel.alignmentY = 0.5f
            costLabel.alignmentY = 0.5f
            versionLabel.alignmentY = 0.5f
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
                val manager = FileEditorManager.getInstance(project)
                manager.closeFile(file)
                val newFile = ClaudeSessionVirtualFile(file.baseName, file.sessionId).apply {
                    workingDir = file.workingDir
                    isWorktreeSession = file.isWorktreeSession
                }
                manager.openFile(newFile, true)
            }
        }

        inner.add(icon)
        inner.add(message)
        inner.add(subtitle)
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(16)))
        inner.add(resumeButton)

        panel.add(inner)
        loadingPanel.add(panel, BorderLayout.CENTER)
        stopLoading()

        // Poll until the external session closes, then enable resume
        val sessionService = ClaudeSessionService.getInstance(project)
        val pollAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, rootDisposable)
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
                    reconnectButton.icon = AllIcons.Actions.Refresh
                    reconnectButton.toolTipText = "Reconnect — close and resume this session"
                }
            }
            summarizeButton?.isEnabled = !active
            refreshTabTitle()
        }

        val onUserInput: () -> Unit = {
            if (file.notifyState != null) {
                file.notifyState = null
                statusService.clearNotifyState(file.sessionId ?: monitoringId)
                refreshTabTitle()
            }
        }

        val onUnresponsive: () -> Unit = {
            file.isUnresponsive = true
            reconnectButton.toolTipText = "Session unresponsive — click to reconnect"
            reconnectButton.icon = AllIcons.General.Error
            refreshTabTitle()
        }

        val session = when {
            isFork -> terminalService.createForkWidget(file.forkFrom!!, rootDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            file.sessionId != null -> terminalService.createResumeWidget(file.sessionId!!, rootDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            isNewWorktree -> terminalService.createNewWorktreeWidget(file.newWorktreeName!!, rootDisposable, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
            else -> terminalService.createNewNamedSessionWidget(file.baseName, rootDisposable, workingDir = file.workingDir, statusFile = statusFile, notifyFile = notifyFile, onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
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
        historyPanel = MessageHistoryPanel(project, session.widget, { file.sessionId }, rootDisposable)
        historySplitter = JBSplitter(false, 0.8f).apply {
            firstComponent = session.widget.component
            secondComponent = null  // starts closed
        }

        // Layout: toolbar(s) on top, splitter in center, context bar at bottom
        val gitDir = file.workingDir ?: project.basePath
        val isGitRepo = gitDir != null && java.io.File(gitDir, ".git").let { it.isDirectory || it.isFile }

        val topPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(toolbar)
            if (isGitRepo) add(createGitToolbar(gitDir!!))
            if (file.isWorktreeSession) add(createWorktreeToolbar())
        }

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(historySplitter!!, BorderLayout.CENTER)
        contentPanel.add(contextBar, BorderLayout.SOUTH)

        loadingPanel.add(contentPanel, BorderLayout.CENTER)

        // Drag-and-drop: accept files dropped onto the terminal
        setupDropTarget(session.widget.component)

        loadingPanel.startLoading()
        AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(::stopLoading, 5L, TimeUnit.SECONDS)

        // Status monitoring
        statusService.startMonitoring(monitoringId, statusFile, notifyFile)
        Disposer.register(rootDisposable, Disposable { statusService.stopMonitoring(monitoringId) })

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
        Disposer.register(rootDisposable, Disposable { statusService.removeStatusListener(statusListener) })

        // Clear idle indicator when user switches to this tab
        val selectionListener = object : FileEditorManagerListener {
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                if (event.newFile == file && file.notifyState == "idle_prompt") {
                    file.notifyState = null
                    statusService.clearNotifyState(file.sessionId ?: monitoringId)
                    refreshTabTitle()
                }
            }
        }
        project.messageBus.connect(rootDisposable).subscribe(
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
        leftPanel.add(createEffortDropdown())

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

        reconnectButton.addActionListener {
            val sessionId = file.sessionId ?: return@addActionListener
            val baseName = file.baseName
            val manager = FileEditorManager.getInstance(project)
            manager.closeFile(file)
            manager.openFile(ClaudeSessionVirtualFile(baseName, sessionId), true)
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

        summarizeButton = javax.swing.JButton("Summarize").apply {
            isFocusable = false
            toolTipText = "Summarize this conversation using a transient fork"
            addActionListener {
                val btn = this
                val mtime = getTranscriptMtime()
                val cached = transientCache["summarize"]
                if (cached != null && cached.first == mtime) {
                    showTransientResult(btn, cached.second)
                } else {
                    runTransientQuery(btn, "Summarize this conversation concisely. Focus on what was decided, what was done, and what's pending. Use markdown formatting.", cacheKey = "summarize")
                }
            }
        }
        rightPanel.add(summarizeButton!!)

        val toolbar = JPanel(BorderLayout())
        toolbar.add(leftPanel, BorderLayout.WEST)
        toolbar.add(rightPanel, BorderLayout.EAST)
        return toolbar
    }

    private fun runTransientQuery(anchor: JComponent, prompt: String, cacheKey: String? = null) {
        val sessionId = file.sessionId ?: return
        val workingDir = file.workingDir ?: project.basePath ?: return

        // Show thinking balloon
        val balloon = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createBalloonBuilder(JBLabel("  Thinking\u2026  "))
            .setFadeoutTime(0)
            .setAnimationCycle(0)
            .setHideOnClickOutside(false)
            .setHideOnAction(false)
            .setFillColor(UIManager.getColor("Panel.background") ?: Color(60, 63, 65))
            .createBalloon()
        balloon.show(
            com.intellij.ui.awt.RelativePoint.getSouthOf(anchor),
            com.intellij.openapi.ui.popup.Balloon.Position.below
        )

        AppExecutorUtil.getAppScheduledExecutorService().execute {
            try {
                val claudeBin = com.clauditor.util.ProcessHelper.which("claude") ?: "claude"
                val process = ProcessBuilder(
                    claudeBin, "-p",
                    "--resume", sessionId,
                    "--fork-session",
                    "--no-session-persistence",
                    "--model", "sonnet",
                    "--append-system-prompt", "You are answering a query from a plugin UI popup. Respond ONLY with the requested content. No conversational filler, no follow-up questions, no commentary about your own actions.",
                    prompt
                ).apply {
                    environment().putAll(com.clauditor.util.ProcessHelper.augmentedEnv())
                    directory(java.io.File(workingDir))
                    redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    redirectErrorStream(true)
                }.start()

                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(120, TimeUnit.SECONDS)
                val result = output.trim()

                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (project.isDisposed) return@invokeLater
                    if (cacheKey != null) {
                        transientCache[cacheKey] = getTranscriptMtime() to result
                    }
                    showTransientResult(anchor, result)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (!project.isDisposed) {
                        showTransientResult(anchor, "Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun runStandaloneQuery(anchor: JComponent, prompt: String, cacheKey: String? = null) {
        val workingDir = file.workingDir ?: project.basePath ?: return

        val balloon = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createBalloonBuilder(JBLabel("  Thinking\u2026  "))
            .setFadeoutTime(0)
            .setAnimationCycle(0)
            .setHideOnClickOutside(false)
            .setHideOnAction(false)
            .setFillColor(UIManager.getColor("Panel.background") ?: Color(60, 63, 65))
            .createBalloon()
        balloon.show(
            com.intellij.ui.awt.RelativePoint.getSouthOf(anchor),
            com.intellij.openapi.ui.popup.Balloon.Position.below
        )

        AppExecutorUtil.getAppScheduledExecutorService().execute {
            try {
                val claudeBin = com.clauditor.util.ProcessHelper.which("claude") ?: "claude"
                val process = ProcessBuilder(
                    claudeBin, "-p",
                    "--no-session-persistence",
                    "--model", "sonnet",
                    "--append-system-prompt", "You are answering a query from a plugin UI popup. Respond ONLY with the requested content. No conversational filler, no follow-up questions, no commentary about your own actions.",
                    prompt
                ).apply {
                    environment().putAll(com.clauditor.util.ProcessHelper.augmentedEnv())
                    directory(java.io.File(workingDir))
                    redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    redirectErrorStream(true)
                }.start()

                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(120, TimeUnit.SECONDS)
                val result = output.trim()

                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (project.isDisposed) return@invokeLater
                    if (cacheKey != null) {
                        transientCache[cacheKey] = getTranscriptMtime() to result
                    }
                    showTransientResult(anchor, result)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (!project.isDisposed) {
                        showTransientResult(anchor, "Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showTransientResult(anchor: JComponent, text: String) {
        val html = markdownToHtml(text)
        val styleConfig = com.intellij.ui.components.JBHtmlPaneStyleConfiguration.builder().apply {
            enableInlineCodeBackground = true
            enableCodeBlocksBackground = true
        }.build()
        val paneConfig = com.intellij.ui.components.JBHtmlPaneConfiguration()
        val htmlPane = com.intellij.ui.components.JBHtmlPane(styleConfig, paneConfig).apply {
            this.text = html
            isEditable = false
            border = JBUI.Borders.empty(8)
        }
        Disposer.register(rootDisposable, htmlPane)

        val scrollPane = javax.swing.JScrollPane(htmlPane).apply {
            preferredSize = java.awt.Dimension(550, 350)
            border = null
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, htmlPane)
            .setTitle("Summary")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun getTranscriptMtime(): Long {
        val sessionId = file.sessionId ?: return 0L
        val basePath = project.basePath ?: return 0L
        val jsonlPath = com.clauditor.util.ClaudePathEncoder.projectDir(basePath).resolve("$sessionId.jsonl")
        return try { Files.getLastModifiedTime(jsonlPath).toMillis() } catch (_: Exception) { 0L }
    }

    private fun markdownToHtml(md: String): String {
        val extensions = listOf(org.commonmark.ext.gfm.tables.TablesExtension.create())
        val parser = org.commonmark.parser.Parser.builder().extensions(extensions).build()
        val renderer = org.commonmark.renderer.html.HtmlRenderer.builder().extensions(extensions).build()
        return renderer.render(parser.parse(md))
    }

    private fun createGitToolbar(gitDir: String): JComponent {
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

        val explainButton = javax.swing.JButton("Explain Changes").apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "No session changes to explain"
        }

        fun refresh() {
            ApplicationManager.getApplication().executeOnPooledThread {
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

                    val sessionFiles = getSessionTouchedFiles()
                    val bySession = if (sessionFiles.isNotEmpty()) {
                        changedFiles.count { it in sessionFiles }
                    } else 0

                    val pureFiles = getPureSessionFiles(gitDir)

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
                        explainButton.isEnabled = bySession > 0
                        explainButton.toolTipText = if (bySession > 0)
                            "Explain changes to $bySession file${if (bySession > 1) "s" else ""}"
                        else "No session changes to explain"
                    }
                } catch (_: Exception) {}
            }
        }

        refresh()

        // Refresh on status updates (proxy for session activity)
        val statusService = ClaudeStatusService.getInstance(project)
        val listener: (String, ClaudeStatus?) -> Unit = { sid, _ ->
            if (sid == file.sessionId) refresh()
        }
        statusService.addStatusListener(listener)
        Disposer.register(rootDisposable, Disposable { statusService.removeStatusListener(listener) })

        commitButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val pureFiles = getPureSessionFiles(gitDir)
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

        explainButton.addActionListener {
            val mtime = getTranscriptMtime()
            val cached = transientCache["explain"]
            if (cached != null && cached.first == mtime) {
                showTransientResult(explainButton, cached.second)
                return@addActionListener
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val sessionFiles = getSessionTouchedFiles()
                    val status = execGit(gitDir, "status", "--porcelain").trim()
                    val changedBySession = if (status.isEmpty()) emptyList() else status.lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            val path = line.drop(3).split(" -> ").last().trim()
                            if (path.isNotEmpty()) java.nio.file.Path.of(gitDir, path).toAbsolutePath().toString() else null
                        }
                        .filter { it in sessionFiles }
                        .map { java.nio.file.Path.of(gitDir).relativize(java.nio.file.Path.of(it)).toString() }

                    if (changedBySession.isEmpty()) return@executeOnPooledThread

                    val diff = execGit(gitDir, "diff", "--", *changedBySession.toTypedArray())
                    val fileList = changedBySession.joinToString(", ")
                    val prompt = "Explain ONLY the following uncommitted changes. Do not discuss any other files or changes. Files: $fileList\n\nFor each file, describe what changed and why based on the conversation context. Output ONLY the explanation in markdown — no preamble, no questions, no commentary.\n\n$diff"

                    ApplicationManager.getApplication().invokeLater {
                        runTransientQuery(explainButton, prompt, cacheKey = "explain")
                    }
                } catch (_: Exception) {}
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
            add(javax.swing.Box.createHorizontalStrut(4))
            add(explainButton)
        }

        val bar = JPanel(BorderLayout())
        bar.add(leftPanel, BorderLayout.WEST)
        return bar
    }

    private fun createWorktreeToolbar(): JComponent {
        val worktreePath = file.workingDir
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
        var isGitHub = false

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
        val prButton = javax.swing.JButton("Create PR").apply {
            isFocusable = false
            isEnabled = false
            isVisible = false
            toolTipText = "Nothing to push"
        }

        fun refreshBranchStatus() {
            if (worktreePath == null || projectPath == null) return
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val wtBranch = execGit(worktreePath, "branch", "--show-current").trim()
                    val mainBranch = execGit(projectPath, "branch", "--show-current").trim()
                    val counts = execGit(worktreePath, "rev-list", "--left-right", "--count", "$mainBranch...$wtBranch").trim()
                    val (behind, ahead) = counts.split("\t").map { it.trim().toIntOrNull() ?: 0 }
                    val dirty = execGit(worktreePath, "status", "--porcelain").trim().isNotEmpty()

                    currentWtBranch = wtBranch
                    currentMainBranch = mainBranch
                    currentAhead = ahead
                    currentBehind = behind
                    hasUncommitted = dirty

                    if (!isGitHub) {
                        val remoteUrl = execGit(worktreePath, "remote", "get-url", "origin").trim()
                        isGitHub = remoteUrl.contains("github.com")
                    }

                    val worktreeName = worktreePath.substringAfterLast('/')
                    val statusText = buildString {
                        append(worktreeName)
                        if (ahead > 0 || behind > 0) {
                            append("  ")
                            if (ahead > 0) append("\u2191$ahead")
                            if (ahead > 0 && behind > 0) append(" ")
                            if (behind > 0) append("\u2193$behind")
                            append(" vs $mainBranch")
                        }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = statusText

                        // Update button: enabled when worktree is behind main
                        updateButton.isEnabled = behind > 0 && !dirty
                        updateButton.toolTipText = when {
                            dirty -> "Commit or stash worktree changes first"
                            behind > 0 -> "Rebase $wtBranch onto $behind new commit${if (behind > 1) "s" else ""} from $mainBranch"
                            else -> "Up to date with $mainBranch"
                        }

                        // Merge button: enabled when ahead > 0 and behind == 0 (fast-forward possible)
                        mergeButton.isEnabled = ahead > 0 && behind == 0
                        mergeButton.toolTipText = when {
                            ahead == 0 -> "Nothing to merge"
                            behind > 0 -> "Update worktree first \u2014 $mainBranch has diverged"
                            else -> "Fast-forward $ahead commit${if (ahead > 1) "s" else ""} into $mainBranch"
                        }

                        // Commit button: enabled when there are uncommitted changes
                        commitButton.isEnabled = dirty
                        commitButton.toolTipText = if (dirty) "Ask Claude to commit changes" else "No uncommitted changes"

                        // PR button: visible for GitHub repos, enabled when there are commits to push
                        prButton.isVisible = isGitHub
                        prButton.isEnabled = isGitHub && ahead > 0 && !dirty
                        prButton.toolTipText = when {
                            !isGitHub -> "Not a GitHub repository"
                            dirty -> "Commit changes first"
                            ahead == 0 -> "No commits to push"
                            else -> "Ask Claude to create a pull request"
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        commitButton.addActionListener {
            commitButton.isEnabled = false
            sendToTerminal("commit all changes with a descriptive commit message\r")
        }

        prButton.addActionListener {
            prButton.isEnabled = false
            sendToTerminal("push this branch and create a GitHub pull request targeting $currentMainBranch with a descriptive title and summary\r")
        }

        updateButton.addActionListener {
            if (worktreePath == null) return@addActionListener
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
        Disposer.register(rootDisposable, Disposable { statusService.removeStatusListener(listener) })

        val openInIdeButton = javax.swing.JButton("Open in IDE").apply {
            isFocusable = false
            toolTipText = "Open worktree directory as a separate project"
            addActionListener {
                if (worktreePath != null) {
                    com.intellij.ide.impl.ProjectUtil.openOrImport(java.nio.file.Path.of(worktreePath), null, false)
                }
            }
        }

        val revealButton = javax.swing.JButton(AllIcons.Actions.MenuOpen).apply {
            isFocusable = false
            toolTipText = "Reveal worktree directory in file manager"
            addActionListener {
                if (worktreePath != null) {
                    com.intellij.ide.actions.RevealFileAction.openDirectory(java.io.File(worktreePath))
                }
            }
        }

        branchLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        statusLabel.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        commitButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        prButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        updateButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        mergeButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT

        val leftPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(branchLabel)
            add(statusLabel)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(commitButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(prButton)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(updateButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(mergeButton)
        }

        openInIdeButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        revealButton.alignmentY = java.awt.Component.CENTER_ALIGNMENT

        val rightPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(openInIdeButton)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(revealButton)
        }

        val bar = JPanel(BorderLayout())
        bar.add(leftPanel, BorderLayout.WEST)
        bar.add(rightPanel, BorderLayout.EAST)
        return bar
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
        val process = com.clauditor.util.ProcessHelper.builder("git", "-C", workDir, *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        return GitResult(process.exitValue(), output)
    }

    private data class SessionFileOp(
        val filePath: String,
        val type: String,           // "Write" or "Edit"
        val content: String? = null, // Write: full file content
        val oldString: String? = null, // Edit
        val newString: String? = null  // Edit
    )

    /** Parse the JSONL for Write/Edit tool uses with full operation details. */
    private fun getSessionFileOperations(): List<SessionFileOp> {
        val sessionId = file.sessionId ?: return emptyList()
        val projectDir = if (file.isWorktreeSession && file.workingDir != null && project.basePath != null) {
            // Extract worktree name from workingDir (last path component)
            val wtName = java.nio.file.Path.of(file.workingDir!!).fileName.toString()
            com.clauditor.util.ClaudePathEncoder.worktreeProjectDir(project.basePath!!, wtName)
        } else {
            com.clauditor.util.ClaudePathEncoder.projectDir(project.basePath ?: return emptyList())
        }
        val jsonlPath = projectDir.resolve("$sessionId.jsonl")
        if (!java.nio.file.Files.exists(jsonlPath)) return emptyList()

        val ops = mutableListOf<SessionFileOp>()
        try {
            java.io.BufferedReader(java.io.FileReader(jsonlPath.toFile())).use { reader ->
                val gson = com.google.gson.Gson()
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val obj = gson.fromJson(line, com.google.gson.JsonObject::class.java)
                        if (obj.get("type")?.asString != "assistant") return@forEach
                        val content = obj.getAsJsonObject("message")
                            ?.getAsJsonArray("content") ?: return@forEach
                        for (block in content) {
                            if (!block.isJsonObject) continue
                            val b = block.asJsonObject
                            if (b.get("type")?.asString != "tool_use") continue
                            val name = b.get("name")?.asString ?: continue
                            val input = b.getAsJsonObject("input") ?: continue
                            val filePath = input.get("file_path")?.asString ?: continue
                            when (name) {
                                "Write" -> ops.add(SessionFileOp(filePath, "Write",
                                    content = input.get("content")?.asString))
                                "Edit" -> ops.add(SessionFileOp(filePath, "Edit",
                                    oldString = input.get("old_string")?.asString,
                                    newString = input.get("new_string")?.asString))
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return ops
    }

    /** Convenience: just the file paths. */
    private fun getSessionTouchedFiles(): Set<String> =
        getSessionFileOperations().map { it.filePath }.toSet()

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
    private fun getPureSessionFiles(gitDir: String): List<String> {
        val ops = getSessionFileOperations()
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

    private fun createEffortDropdown(): JComponent {
        val levels = arrayOf("low", "medium", "high", "max")

        val combo = javax.swing.JComboBox(levels)
        combo.selectedItem = "medium"
        combo.isFocusable = false

        combo.addActionListener {
            val selected = combo.selectedItem as? String ?: return@addActionListener
            sendToTerminal("/effort $selected\r")
        }

        return combo
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
        Disposer.register(rootDisposable, Disposable { statusService.removeStatusListener(listener) })

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
                persistence.add(candidate.sessionId)

                // Migrate status monitoring from temp to real session ID
                statusService.stopMonitoring(tempMonitoringId)
                val realStatusFile = statusService.createStatusFilePath(candidate.sessionId)
                try {
                    if (Files.exists(tempStatusFile)) {
                        Files.move(tempStatusFile, realStatusFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (_: Exception) {}
                // Keep the original notify file path — the process env var hasn't changed
                statusService.startMonitoring(candidate.sessionId, realStatusFile, notifyFile)

                refreshTabTitle()
                sessionService.removeChangeListener(linkListener)
            }
        }
        sessionService.addChangeListener(linkListener)
        Disposer.register(rootDisposable, Disposable { sessionService.removeChangeListener(linkListener) })
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
            remainingLabel.text = when {
                status.contextRemainingTokens != null && status.contextWindowSize != null ->
                    "${formatTokens(status.contextRemainingTokens)} / ${formatTokens(status.contextWindowSize)}"
                status.contextRemainingTokens != null ->
                    "${formatTokens(status.contextRemainingTokens)} left"
                else -> ""
            }
            costLabel.text = status.costUsd?.let { String.format("$%.2f", it) } ?: ""
            status.cliVersion?.let { current ->
                versionLinkTarget = current.replace('.', '-')
                versionLabel.toolTipText = "View release notes for v$current"
                val statusService = ClaudeStatusService.getInstance(project)
                statusService.refreshLatestCliVersion()
                val latest = statusService.getLatestCliVersion()
                val hasUpdate = latest != null && current != latest
                if (hasUpdate) {
                    versionLabel.text = "v$current (update: v$latest)"
                    versionLabel.foreground = COLOR_YELLOW
                    reconnectButton.icon = com.intellij.icons.AllIcons.Actions.ForceRefresh
                    reconnectButton.toolTipText = "Reconnect — update available (v$current \u2192 v$latest)"
                } else {
                    versionLabel.text = "v$current"
                    versionLabel.foreground = UIManager.getColor("Label.disabledForeground")
                    reconnectButton.icon = com.intellij.icons.AllIcons.Actions.Refresh
                    reconnectButton.toolTipText = "Reconnect — close and resume this session"
                }
            }
            contextBar.isVisible = true
        }
    }

    private fun refreshTabTitle() {
        val newTitle = file.computeTabTitle()
        if (newTitle == lastTitle) return
        lastTitle = newTitle
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
    }
}
