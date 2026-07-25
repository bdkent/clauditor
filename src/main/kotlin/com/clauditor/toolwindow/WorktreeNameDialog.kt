package com.clauditor.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class WorktreeNameDialog(private val project: Project) : DialogWrapper(project) {

    private val nameField = JBTextField(24)
    private val baseBranchLabel = JBLabel(" ")
    private val statusLabel = JBLabel(" ")
    private val pruneButton = JButton("Prune stale registration").apply { isVisible = false }

    private var worktreeEntries: List<WorktreeEntry> = emptyList()
    private var entriesLoaded = false
    private var validation: Validation = Validation.Empty

    var enteredName: String? = null
        private set

    init {
        title = "New Worktree Session"
        setOKButtonText("Create")
        init()
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { revalidateInput() }
        })
        pruneButton.addActionListener { runPrune() }
        setOKActionEnabled(false)
        loadWorktreeList()
        loadBaseBranchInfo()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = JBUI.size(520, 160)
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 2, 2, 2)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 1.0
        }
        panel.add(JBLabel("Worktree name:"), gbc)
        gbc.gridy++
        panel.add(nameField, gbc)
        gbc.gridy++
        panel.add(baseBranchLabel, gbc)
        gbc.gridy++
        panel.add(statusLabel, gbc)
        gbc.gridy++
        gbc.fill = GridBagConstraints.NONE
        panel.add(pruneButton, gbc)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doOKAction() {
        if (validation is Validation.Ok || validation is Validation.Info) {
            enteredName = nameField.text.trim()
            super.doOKAction()
        }
    }

    private fun loadWorktreeList() {
        val basePath = project.basePath ?: return
        val modality = ModalityState.stateForComponent(contentPane)
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = WorktreeInspector.list(basePath)
            ApplicationManager.getApplication().invokeLater({
                worktreeEntries = list
                entriesLoaded = true
                revalidateInput()
            }, modality)
        }
    }

    private fun loadBaseBranchInfo() {
        val basePath = project.basePath ?: return
        baseBranchLabel.text = "Checking base branch state..."
        baseBranchLabel.foreground = JBColor.foreground()
        val modality = ModalityState.stateForComponent(contentPane)
        com.clauditor.util.ClauditorExecutor.submit {
            val info = WorktreeInspector.inspectBaseBranch(basePath)
            ApplicationManager.getApplication().invokeLater({ renderBaseBranch(info) }, modality)
        }
    }

    private fun renderBaseBranch(info: BaseBranchInfo) {
        val def = info.defaultBranch ?: "main"
        val rule = "Claude bases new worktrees on origin/$def."
        val (text, color) = when (info.state) {
            BaseBranchState.SYNCED -> rule to JBColor.foreground()
            BaseBranchState.LOCAL_AHEAD -> "$rule Your local $def is ${info.ahead} ahead — those commits won't be in the new worktree." to JBColor.ORANGE
            BaseBranchState.LOCAL_BEHIND -> "$rule Your local $def is ${info.behind} behind — the worktree will include commits you don't have locally." to JBColor.ORANGE
            BaseBranchState.LOCAL_DIVERGED -> "$rule Your local $def has diverged (${info.ahead} ahead, ${info.behind} behind)." to JBColor.ORANGE
            BaseBranchState.OTHER_BRANCH -> "$rule You are on '${info.currentBranch ?: "(unknown)"}' — the worktree will not branch from your current work." to JBColor.ORANGE
            BaseBranchState.NO_LOCAL_DEFAULT -> "$rule You have no local '$def' branch — the worktree will be created from the remote ref." to JBColor.foreground()
            BaseBranchState.NO_REMOTE_DEFAULT -> "No origin/HEAD set — Claude may fail to create the worktree or use an unexpected base." to JBColor.RED
            BaseBranchState.UNKNOWN -> "Couldn't determine base branch state." to JBColor.ORANGE
        }
        baseBranchLabel.text = "<html>$text</html>"
        baseBranchLabel.foreground = color
        baseBranchLabel.toolTipText = info.errorDetail
    }

    private fun runPrune() {
        val basePath = project.basePath ?: return
        pruneButton.isEnabled = false
        statusLabel.text = "Pruning..."
        statusLabel.foreground = JBColor.foreground()
        val modality = ModalityState.stateForComponent(contentPane)
        ApplicationManager.getApplication().executeOnPooledThread {
            val (ok, msg) = WorktreeInspector.prune(basePath)
            ApplicationManager.getApplication().invokeLater({
                pruneButton.isEnabled = true
                if (ok) {
                    loadWorktreeList()
                } else {
                    statusLabel.text = "Prune failed: ${msg.take(120)}"
                    statusLabel.foreground = JBColor.RED
                }
            }, modality)
        }
    }

    private fun revalidateInput() {
        val basePath = project.basePath
        val raw = nameField.text.trim()
        validation = when {
            raw.isEmpty() -> Validation.Empty
            !entriesLoaded -> Validation.Loading
            else -> validateName(basePath, raw)
        }
        applyValidationToUi()
    }

    private fun validateName(basePath: String?, raw: String): Validation {
        val normalized = WorktreeInspector.normalize(raw)
        val expectedBranch = "worktree-$normalized"
        val expectedPath = basePath?.let { Path.of(it, ".claude", "worktrees", normalized).toAbsolutePath() }
        val expectedPathStr = expectedPath?.toString()
        val match = worktreeEntries.firstOrNull {
            it.branch == expectedBranch || (expectedPathStr != null && it.path == expectedPathStr)
        }
        val pathExists = expectedPath?.let { Files.exists(it) } ?: false
        return when {
            match != null && match.prunable -> Validation.PrunableStale(
                "Stale git worktree '$expectedBranch' (${match.prunableReason ?: "prunable"}). Prune to reuse this name."
            )
            match != null && match.path == expectedPathStr && pathExists -> Validation.Info(
                "Existing worktree will be reused."
            )
            match != null -> Validation.Blocked(
                "Worktree '$expectedBranch' is already registered at ${match.path}."
            )
            pathExists -> Validation.Blocked(
                "Directory $expectedPath exists but isn't a registered worktree. Remove it before reusing this name."
            )
            else -> Validation.Ok
        }
    }

    private fun applyValidationToUi() {
        when (val v = validation) {
            Validation.Empty -> {
                statusLabel.text = " "
                statusLabel.foreground = JBColor.foreground()
                pruneButton.isVisible = false
                setOKActionEnabled(false)
            }
            Validation.Loading -> {
                statusLabel.text = "Checking existing worktrees..."
                statusLabel.foreground = JBColor.foreground()
                pruneButton.isVisible = false
                setOKActionEnabled(false)
            }
            Validation.Ok -> {
                statusLabel.text = " "
                statusLabel.foreground = JBColor.foreground()
                pruneButton.isVisible = false
                setOKActionEnabled(true)
            }
            is Validation.Info -> {
                statusLabel.text = v.message
                statusLabel.foreground = JBColor.foreground()
                pruneButton.isVisible = false
                setOKActionEnabled(true)
            }
            is Validation.PrunableStale -> {
                statusLabel.text = v.message
                statusLabel.foreground = JBColor.ORANGE
                pruneButton.isVisible = true
                setOKActionEnabled(false)
            }
            is Validation.Blocked -> {
                statusLabel.text = v.message
                statusLabel.foreground = JBColor.RED
                pruneButton.isVisible = false
                setOKActionEnabled(false)
            }
        }
    }

    private sealed class Validation {
        object Empty : Validation()
        object Loading : Validation()
        object Ok : Validation()
        data class Info(val message: String) : Validation()
        data class PrunableStale(val message: String) : Validation()
        data class Blocked(val message: String) : Validation()
    }
}
