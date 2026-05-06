package com.clauditor.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class ClauditorSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var transientModelCombo: ComboBox<String>? = null
    private var echoTimeoutSpinner: JSpinner? = null
    private var claudeBinaryField: JBTextField? = null
    private var defaultArgsField: JBTextField? = null
    private var envColortermCheckbox: JBCheckBox? = null
    private var envDisableTrafficCheckbox: JBCheckBox? = null
    private var envSkipUpdateCheckbox: JBCheckBox? = null
    private var envDisableCachingCheckbox: JBCheckBox? = null
    private var customEnvVarsArea: JBTextArea? = null
    private var refreshIntervalSpinner: JSpinner? = null
    private var branchRefreshSpinner: JSpinner? = null

    override fun getDisplayName(): String = "Clauditor"

    override fun createComponent(): JComponent {
        transientModelCombo = ComboBox(DefaultComboBoxModel(arrayOf("haiku", "sonnet", "opus"))).apply {
            toolTipText = "Model used for Summarize, Explain Changes, and Review Memory popup queries"
        }

        echoTimeoutSpinner = JSpinner(SpinnerNumberModel(3000, 1000, 30000, 500)).apply {
            toolTipText = "Milliseconds after user input with no response before marking a session as unresponsive"
        }

        claudeBinaryField = JBTextField().apply {
            emptyText.text = "Auto-detect from PATH"
            toolTipText = "Absolute path to the claude binary. Leave empty to auto-detect."
        }

        defaultArgsField = JBTextField().apply {
            emptyText.text = "e.g. --model opus --verbose"
            toolTipText = "Extra CLI arguments appended when launching new sessions (space-separated)"
        }

        refreshIntervalSpinner = JSpinner(SpinnerNumberModel(60, 0, 3600, 10)).apply {
            toolTipText = "Re-run the status line command every N seconds. 0 means event-driven only (default updates after each assistant message)."
        }

        branchRefreshSpinner = JSpinner(SpinnerNumberModel(10, 0, 600, 5)).apply {
            toolTipText = "Re-query git state for the worktree/git toolbars every N seconds. 0 = refresh only on tab focus and Claude status events. Takes effect on next session restart."
        }

        envColortermCheckbox = JBCheckBox("COLORTERM=truecolor — Enable true color support in terminal output")
        envDisableTrafficCheckbox = JBCheckBox("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 — Disable telemetry")
        envSkipUpdateCheckbox = JBCheckBox("CLAUDE_CODE_SKIP_UPDATE_CHECK=1 — Skip update check on launch")
        envDisableCachingCheckbox = JBCheckBox("DISABLE_PROMPT_CACHING=1 — Disable prompt caching")

        customEnvVarsArea = JBTextArea(3, 40).apply {
            emptyText.text = "KEY=VALUE (one per line)"
            toolTipText = "Additional environment variables passed to claude sessions, one KEY=VALUE per line"
        }

        val envDocsLink = JBLabel("<html><a href=''>Claude Code environment variable docs</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    BrowserUtil.browse("https://docs.anthropic.com/en/docs/claude-code/settings#environment-variables")
                }
            })
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Transient query model:"), transientModelCombo!!, 1, false)
            .addComponentToRightColumn(createHint("Model for Summarize, Explain Changes, and Review Memory popups"), 0)
            .addSeparator()
            .addLabeledComponent(JBLabel("Unresponsive timeout (ms):"), echoTimeoutSpinner!!, 1, false)
            .addComponentToRightColumn(createHint("Time before a terminal session is flagged as unresponsive"), 0)
            .addSeparator()
            .addLabeledComponent(JBLabel("Claude binary path:"), claudeBinaryField!!, 1, false)
            .addComponentToRightColumn(createHint("Leave empty to auto-detect via PATH"), 0)
            .addSeparator()
            .addLabeledComponent(JBLabel("Default session arguments:"), defaultArgsField!!, 1, false)
            .addComponentToRightColumn(createHint("Extra CLI flags appended to every new session launch"), 0)
            .addSeparator()
            .addLabeledComponent(JBLabel("Status line refresh (sec):"), refreshIntervalSpinner!!, 1, false)
            .addComponentToRightColumn(createHint("Re-run status line every N seconds. 0 = event-driven only. Requires CLI 2.1.97+"), 0)
            .addSeparator()
            .addLabeledComponent(JBLabel("Branch status refresh (sec):"), branchRefreshSpinner!!, 1, false)
            .addComponentToRightColumn(createHint("Re-query git for the worktree/git toolbars every N seconds. 0 = focus + status events only."), 0)
            .addSeparator()
            .addComponent(JBLabel("Environment Variables").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(8)
            })
            .addComponent(envDocsLink)
            .addComponent(envColortermCheckbox!!)
            .addComponent(envDisableTrafficCheckbox!!)
            .addComponent(envSkipUpdateCheckbox!!)
            .addComponent(envDisableCachingCheckbox!!)
            .addLabeledComponent(JBLabel("Custom env vars:"), JScrollPane(customEnvVarsArea), 1, true)
            .addComponentToRightColumn(createHint("One KEY=VALUE per line"), 0)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = ClauditorSettings.getInstance()
        return transientModelCombo?.item != settings.state.transientQueryModel ||
            (echoTimeoutSpinner?.value as? Int) != settings.state.echoTimeoutMs ||
            claudeBinaryField?.text?.trim() != settings.state.claudeBinaryPath ||
            defaultArgsField?.text?.trim() != settings.state.defaultSessionArgs ||
            envColortermCheckbox?.isSelected != settings.state.envColorterm ||
            envDisableTrafficCheckbox?.isSelected != settings.state.envDisableNonessentialTraffic ||
            envSkipUpdateCheckbox?.isSelected != settings.state.envSkipUpdateCheck ||
            envDisableCachingCheckbox?.isSelected != settings.state.envDisablePromptCaching ||
            customEnvVarsArea?.text?.trim() != settings.state.customEnvVars ||
            (refreshIntervalSpinner?.value as? Int) != settings.state.statusLineRefreshInterval ||
            (branchRefreshSpinner?.value as? Int) != settings.state.branchStatusRefreshSeconds
    }

    override fun apply() {
        val settings = ClauditorSettings.getInstance()
        settings.state.transientQueryModel = transientModelCombo?.item ?: "sonnet"
        settings.state.echoTimeoutMs = (echoTimeoutSpinner?.value as? Int) ?: 3000
        settings.state.claudeBinaryPath = claudeBinaryField?.text?.trim() ?: ""
        settings.state.defaultSessionArgs = defaultArgsField?.text?.trim() ?: ""
        settings.state.envColorterm = envColortermCheckbox?.isSelected ?: false
        settings.state.envDisableNonessentialTraffic = envDisableTrafficCheckbox?.isSelected ?: false
        settings.state.envSkipUpdateCheck = envSkipUpdateCheckbox?.isSelected ?: false
        settings.state.envDisablePromptCaching = envDisableCachingCheckbox?.isSelected ?: false
        settings.state.customEnvVars = customEnvVarsArea?.text?.trim() ?: ""
        settings.state.statusLineRefreshInterval = (refreshIntervalSpinner?.value as? Int) ?: 60
        settings.state.branchStatusRefreshSeconds = (branchRefreshSpinner?.value as? Int) ?: 10
    }

    override fun reset() {
        val settings = ClauditorSettings.getInstance()
        transientModelCombo?.item = settings.state.transientQueryModel
        echoTimeoutSpinner?.value = settings.state.echoTimeoutMs
        claudeBinaryField?.text = settings.state.claudeBinaryPath
        defaultArgsField?.text = settings.state.defaultSessionArgs
        envColortermCheckbox?.isSelected = settings.state.envColorterm
        envDisableTrafficCheckbox?.isSelected = settings.state.envDisableNonessentialTraffic
        envSkipUpdateCheckbox?.isSelected = settings.state.envSkipUpdateCheck
        envDisableCachingCheckbox?.isSelected = settings.state.envDisablePromptCaching
        customEnvVarsArea?.text = settings.state.customEnvVars
        refreshIntervalSpinner?.value = settings.state.statusLineRefreshInterval
        branchRefreshSpinner?.value = settings.state.branchStatusRefreshSeconds
    }

    override fun disposeUIResources() {
        panel = null
        transientModelCombo = null
        echoTimeoutSpinner = null
        claudeBinaryField = null
        defaultArgsField = null
        envColortermCheckbox = null
        envDisableTrafficCheckbox = null
        envSkipUpdateCheckbox = null
        envDisableCachingCheckbox = null
        customEnvVarsArea = null
        refreshIntervalSpinner = null
        branchRefreshSpinner = null
    }

    private fun createHint(text: String): JComponent {
        return JBLabel(text).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        }
    }
}
