package com.clauditor.toolwindow

import com.clauditor.services.ClaudeStatusService
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.JBUI
import com.clauditor.util.RoundedProgressBarUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.ByteArrayInputStream
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.UIManager
import javax.xml.parsers.DocumentBuilderFactory

class ClaudeStatusBarPanel(private val project: Project) : JPanel(), Disposable {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ClaudeStatusBarPanel::class.java)
    private val fiveHourBar = rateMeter("—")
    private val sevenDayBar = rateMeter("—")
    private val authLabel = JBLabel("")
    private val authButton = JButton("Login")
    private val systemIcon = JBLabel(AllIcons.General.InspectionsOK)
    private val systemLabel = JBLabel("")
    private val statusLink = HyperlinkLabel("status.claude.com")
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val gson = Gson()
    var isVertical = true
        private set
    private val contentPanel = JPanel()

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(2, 8)

        add(contentPanel, BorderLayout.NORTH)

        authButton.addActionListener { toggleAuth() }
        statusLink.addHyperlinkListener { BrowserUtil.browse("https://status.claude.com") }

        val statusService = ClaudeStatusService.getInstance(project)
        statusService.addStatusListener { _, _ ->
            ApplicationManager.getApplication().invokeLater { updateRateLimits() }
        }
        Disposer.register(project, this)

        rebuildLayout()
        refreshAll()
        schedulePoll()
    }

    fun toggleLayout() {
        isVertical = !isVertical
        rebuildLayout()
    }

    private fun rebuildLayout() {
        contentPanel.removeAll()
        contentPanel.isOpaque = false

        if (isVertical) {
            contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
            contentPanel.add(row(JBLabel("5h"), fiveHourBar, JBLabel("7d"), sevenDayBar))
            contentPanel.add(row(authLabel, authButton))
            contentPanel.add(row(systemIcon, systemLabel, statusLink))
        } else {
            contentPanel.layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))
            contentPanel.add(row(JBLabel("5h"), fiveHourBar, JBLabel("7d"), sevenDayBar))
            contentPanel.add(sep())
            contentPanel.add(row(authLabel, authButton))
            contentPanel.add(sep())
            contentPanel.add(row(systemIcon, systemLabel, statusLink))
        }

        val minH = if (isVertical) JBUI.scale(80) else JBUI.scale(32)
        minimumSize = Dimension(0, minH)

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun row(vararg components: JComponent) = JPanel(
        FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)
    ).apply {
        isOpaque = false
        for (c in components) add(c)
    }

    private fun sep() = JBLabel("|").apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    // --- Rate Limits ---

    private fun rateMeter(label: String) = JProgressBar(0, 100).apply {
        setUI(RoundedProgressBarUI())
        isStringPainted = true
        string = label
        value = 0
        isOpaque = false
        border = JBUI.Borders.empty(3)
        preferredSize = Dimension(JBUI.scale(100), JBUI.scale(22))
    }

    private val COLOR_GREEN = Color(0x5B, 0xA8, 0x5B)
    private val COLOR_YELLOW = Color(0xD4, 0xA0, 0x1E)
    private val COLOR_RED = Color(0xD4, 0x4B, 0x4B)

    private fun updateRateLimits() {
        val all = ClaudeStatusService.getInstance(project).getAllStatuses()
        if (all.isEmpty()) {
            resetBar(fiveHourBar); resetBar(sevenDayBar)
            return
        }
        val latest = all.values.last()
        updateBar(fiveHourBar, latest.fiveHourRatePercent, latest.fiveHourResetsAt, 5 * 3600L)
        updateBar(sevenDayBar, latest.sevenDayRatePercent, latest.sevenDayResetsAt, 7 * 86400L)
    }

    private fun updateBar(bar: JProgressBar, usedPercent: Double?, resetsAt: Long?, windowSeconds: Long) {
        if (usedPercent == null) { resetBar(bar); return }
        val pct = usedPercent.toInt().coerceIn(0, 100)
        bar.value = pct
        bar.string = "$pct%"
        bar.foreground = burnColor(usedPercent, resetsAt, windowSeconds)
    }

    private fun resetBar(bar: JProgressBar) {
        bar.value = 0; bar.string = "\u2014"; bar.foreground = COLOR_GREEN
    }

    /**
     * Compares used% against the fraction of the window that has elapsed.
     * If used% is well ahead of elapsed%, the user is burning too fast.
     */
    private fun burnColor(usedPercent: Double, resetsAt: Long?, windowSeconds: Long): Color {
        if (resetsAt == null) return defaultBurnColor(usedPercent)
        val nowEpoch = System.currentTimeMillis() / 1000
        val remaining = (resetsAt - nowEpoch).coerceAtLeast(0)
        val elapsed = windowSeconds - remaining
        if (elapsed < windowSeconds / 20) return defaultBurnColor(usedPercent) // <5% elapsed, not enough signal
        val elapsedFraction = elapsed.toDouble() / windowSeconds
        val sustainablePercent = elapsedFraction * 100.0
        val ratio = usedPercent / sustainablePercent
        return when {
            ratio <= 1.0 -> COLOR_GREEN
            ratio <= 1.5 -> COLOR_YELLOW
            else -> COLOR_RED
        }
    }

    /** Fallback when resets_at is unavailable — simple threshold. */
    private fun defaultBurnColor(usedPercent: Double): Color = when {
        usedPercent < 50 -> COLOR_GREEN
        usedPercent < 80 -> COLOR_YELLOW
        else -> COLOR_RED
    }

    // --- Auth ---

    private fun refreshAuth() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val claudePath = com.clauditor.settings.ClauditorSettings.getInstance().resolveClaudeBinary()
                log.info("Clauditor: refreshAuth — claude binary resolved to: $claudePath")

                val proc = com.clauditor.util.ProcessHelper.builder("claude", "auth", "status")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                log.info("Clauditor: 'claude auth status' exit=$exitCode, output=${out.take(500)}")

                // Output may contain non-JSON lines (warnings, prompts) — extract the JSON object
                val jsonStr = out.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else null }
                    ?.substringBeforeLast("}")?.plus("}")
                val obj = if (jsonStr != null) gson.fromJson(jsonStr, JsonObject::class.java) else null
                val loggedIn = obj?.get("loggedIn")?.asBoolean ?: false
                val email = obj?.get("email")?.asString ?: ""
                val sub = obj?.get("subscriptionType")?.asString ?: ""

                log.info("Clauditor: auth parsed — loggedIn=$loggedIn, email=$email, sub=$sub")

                ApplicationManager.getApplication().invokeLater {
                    if (loggedIn) {
                        val short = if (email.length > 50) email.take(47) + "\u2026" else email
                        authLabel.text = "$short ($sub)"
                        authButton.text = "Logout"
                    } else {
                        authLabel.text = "Not logged in"
                        authButton.text = "Login"
                    }
                    authButton.isEnabled = true
                }
            } catch (e: java.io.IOException) {
                log.warn("Clauditor: refreshAuth IOException — claude CLI not found", e)
                ApplicationManager.getApplication().invokeLater {
                    authLabel.text = "claude CLI not found"
                    authButton.isEnabled = false
                }
            } catch (e: Exception) {
                log.warn("Clauditor: refreshAuth failed", e)
                ApplicationManager.getApplication().invokeLater {
                    authLabel.text = "auth check failed"
                    authButton.isEnabled = true
                }
            }
        }
    }

    private fun toggleAuth() {
        val isLogout = authButton.text == "Logout"
        authButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cmd = if (isLogout) arrayOf("claude", "logout") else arrayOf("claude", "login")
                val proc = com.clauditor.util.ProcessHelper.builder(*cmd).start()
                proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                if (proc.isAlive) proc.destroyForcibly()
            } catch (_: Exception) {}

            ApplicationManager.getApplication().invokeLater {
                authButton.isEnabled = true
                refreshAuth()
            }
        }
    }

    // --- System Status ---

    private fun refreshSystemStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val xml = HttpRequests.request("https://status.claude.com/history.atom")
                    .connectTimeout(5000)
                    .readTimeout(5000)
                    .readString()

                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                val doc = factory.newDocumentBuilder()
                    .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

                val entries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry")
                if (entries.length == 0) {
                    setSystemStatus("All systems operational", "ok")
                    return@executeOnPooledThread
                }

                val first = entries.item(0) as org.w3c.dom.Element
                val title = first.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "title")
                    .item(0)?.textContent ?: ""
                val content = first.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "content")
                    .item(0)?.textContent ?: ""

                val firstLabel = Regex("<strong>(\\w+)</strong>").find(content)?.groupValues?.get(1)
                val level = when (firstLabel) {
                    "Resolved" -> "ok"
                    "Monitoring" -> "warning"
                    else -> "error"
                }
                val display = if (level == "ok") "All clear" else title
                setSystemStatus(display, level)
            } catch (_: Exception) {
                setSystemStatus("Unavailable", "unknown")
            }
        }
    }

    private fun setSystemStatus(message: String, level: String) {
        ApplicationManager.getApplication().invokeLater {
            systemIcon.icon = when (level) {
                "ok" -> AllIcons.General.InspectionsOK
                "warning" -> AllIcons.General.Warning
                "error" -> AllIcons.General.Error
                else -> AllIcons.General.Information
            }
            systemLabel.text = if (message.length > 45) message.take(42) + "\u2026" else message
        }
    }

    // --- Polling ---

    private fun refreshAll() {
        updateRateLimits()
        refreshAuth()
        refreshSystemStatus()
    }

    private fun schedulePoll() {
        if (pollAlarm.isDisposed) return
        pollAlarm.addRequest({
            ApplicationManager.getApplication().invokeLater { updateRateLimits() }
            refreshAuth()
            refreshSystemStatus()
            schedulePoll()
        }, 60_000)
    }

    override fun dispose() {}
}
