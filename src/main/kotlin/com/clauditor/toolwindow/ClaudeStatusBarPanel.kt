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
    private val usageLink = HyperlinkLabel("Usage & budget on claude.ai")
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val gson = Gson()
    var isVertical = true
        private set
    private val contentPanel = JPanel()
    private var statusListener: ((String, com.clauditor.model.ClaudeStatus?) -> Unit)? = null

    /** Coarse plan tier from `claude auth status` (free|pro|max|team|enterprise|null). Drives whether the 5h/7d bars make sense. */
    @Volatile private var subscriptionType: String? = null
    /** Whether the 5h/7d row is currently shown — tracked so we only rebuild the layout when the gate decision flips. */
    private var rateLimitsVisible: Boolean = true

    /** Whether this tool window is on screen; gates the CLI + network refreshes. */
    @Volatile private var visible = false
    @Volatile private var lastExpensiveRefresh = 0L

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(2, 8)

        add(contentPanel, BorderLayout.NORTH)

        authButton.addActionListener { toggleAuth() }
        statusLink.addHyperlinkListener { BrowserUtil.browse("https://status.claude.com") }
        usageLink.addHyperlinkListener { BrowserUtil.browse("https://claude.ai/settings/usage") }

        val statusService = ClaudeStatusService.getInstance(project)
        log.info("Clauditor[${project.name}]: StatusBarPanel init — panel=${System.identityHashCode(this)}, service=${System.identityHashCode(statusService)}")
        val listener: (String, com.clauditor.model.ClaudeStatus?) -> Unit = { sid, _ ->
            log.info("Clauditor[${project.name}]: StatusBarPanel listener fired for $sid, panel=${System.identityHashCode(this)}")
            ApplicationManager.getApplication().invokeLater {
                updateRateLimits()
                refreshRateLimitVisibility()
            }
        }
        statusService.addStatusListener(listener)
        statusListener = listener
        Disposer.register(project, this)

        rebuildLayout()
        trackVisibility()
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

        val showRates = shouldShowRateLimits()
        rateLimitsVisible = showRates
        // The 5h/7d bars only mean something on subscription plans (Pro/Max). On
        // non-subscription plans (e.g. enterprise) they're always empty, so swap in
        // a link to where real usage/budget lives instead — see shouldShowRateLimits().
        val usageRow = if (showRates) row(JBLabel("5h"), fiveHourBar, JBLabel("7d"), sevenDayBar) else row(usageLink)

        if (isVertical) {
            contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
            contentPanel.add(usageRow)
            contentPanel.add(row(authLabel, authButton))
            contentPanel.add(row(systemIcon, systemLabel, statusLink))
        } else {
            contentPanel.layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))
            contentPanel.add(usageRow)
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

    /** Whether the 5h/7d bars are meaningful for the current plan. Logic lives in [rateLimitsVisibleFor] (pure, tested). */
    private fun shouldShowRateLimits(): Boolean =
        rateLimitsVisibleFor(subscriptionType, anyRateLimitsSeen())

    private fun anyRateLimitsSeen(): Boolean =
        ClaudeStatusService.getInstance(project).getAllStatuses().values.any {
            it.fiveHourRatePercent != null || it.sevenDayRatePercent != null
        }

    /** Rebuild only when the gate decision changes, so polling/status ticks don't thrash the layout. Must run on the EDT. */
    private fun refreshRateLimitVisibility() {
        if (shouldShowRateLimits() != rateLimitsVisible) rebuildLayout()
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

                val res = com.clauditor.util.ProcessHelper.execWithTimeout(
                    command = arrayOf("claude", "auth", "status"),
                    timeoutMs = 15_000
                )
                val out = res.output
                val exitCode = res.exitCode
                log.info("Clauditor: 'claude auth status' exit=$exitCode, output=${out.take(500)}")

                // Output may contain non-JSON lines (warnings, prompts) — extract the JSON object
                val jsonStr = out.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else null }
                    ?.substringBeforeLast("}")?.plus("}")
                val obj = if (jsonStr != null) gson.fromJson(jsonStr, JsonObject::class.java) else null
                val loggedIn = obj?.get("loggedIn")?.asBoolean ?: false
                val email = obj?.get("email")?.asString ?: ""
                val sub = obj?.get("subscriptionType")?.asString ?: ""
                subscriptionType = sub.ifBlank { null }

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
                    refreshRateLimitVisibility()
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
        lastExpensiveRefresh = System.currentTimeMillis()
        refreshAuth()
        refreshSystemStatus()
    }

    /**
     * Periodic refresh, split by what each part actually costs.
     *
     * `updateRateLimits` just re-renders numbers the status service already has, so it stays
     * on the fast tick. `refreshAuth` spawns the Claude CLI (~0.8 s) and `refreshSystemStatus`
     * makes an outbound HTTPS request — both previously ran every 60 s forever, even with this
     * tool window closed. Neither answer changes on that timescale, so they now run at
     * [EXPENSIVE_REFRESH_MS] and only while the panel is on screen.
     */
    private fun schedulePoll() {
        if (pollAlarm.isDisposed) return
        pollAlarm.addRequest({
            if (visible) {
                ApplicationManager.getApplication().invokeLater { updateRateLimits() }
                refreshExpensiveIfStale()
            }
            schedulePoll()
        }, 60_000)
    }

    /** Re-run the CLI/network checks only if their last result has aged out. */
    private fun refreshExpensiveIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastExpensiveRefresh < EXPENSIVE_REFRESH_MS) return
        lastExpensiveRefresh = now
        refreshAuth()
        refreshSystemStatus()
    }

    /** Show current data as soon as the panel is opened, rather than waiting for a tick. */
    private fun trackVisibility() {
        visible = isShowing
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) return@addHierarchyListener
            val nowVisible = isShowing
            val became = nowVisible && !visible
            visible = nowVisible
            if (became) {
                updateRateLimits()
                refreshExpensiveIfStale()
            }
        }
    }

    private companion object {
        /** How often the CLI auth check and the status.claude.com fetch may re-run. */
        const val EXPENSIVE_REFRESH_MS = 15 * 60 * 1000L
    }

    override fun dispose() {
        statusListener?.let { ClaudeStatusService.getInstance(project).removeStatusListener(it) }
        statusListener = null
    }
}
