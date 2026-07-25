package com.clauditor.toolwindow

import com.clauditor.util.ClaudePathEncoder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Container
import java.nio.file.Files
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

data class MessageEntry(
    val text: String,
    val searchKey: String
)

/**
 * Reads user messages from the session JSONL and displays them as a clickable list.
 * Clicking searches the terminal text buffer for the message and scrolls to it.
 */
class MessageHistoryPanel(
    private val project: Project,
    private val widget: JBTerminalWidget,
    private val sessionIdProvider: () -> String?,
    private val workingDirProvider: () -> String?,
    parent: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(MessageHistoryPanel::class.java)
    private val listModel = DefaultListModel<MessageEntry>()
    private val list = JBList(listModel)
    private val gson = Gson()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    // Transcripts reach tens of MB, so parse only what was appended since the last pass
    // rather than the whole file every 3s. [entries] accumulates across passes.
    private val entries = mutableListOf<MessageEntry>()
    private val tail = com.clauditor.util.JsonlTailReader()
    private var lastPath: java.nio.file.Path? = null
    @Volatile private var visible = false

    init {
        border = JBUI.Borders.empty()
        preferredSize = java.awt.Dimension(JBUI.scale(220), 0)

        list.cellRenderer = MessageEntryRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx < 0) return
                if (!list.getCellBounds(idx, idx).contains(e.point)) return
                scrollToMessage(idx)
            }
        })

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            val label = JLabel("Messages")
            label.font = label.font.deriveFont(java.awt.Font.BOLD)
            add(label, BorderLayout.WEST)
        }

        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)

        Disposer.register(parent, this)
        trackVisibility()
        ApplicationManager.getApplication().executeOnPooledThread { loadMessages() }
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (refreshAlarm.isDisposed) return
        refreshAlarm.addRequest({
            // Nothing to show while the sidebar is collapsed, and the tail read makes
            // catching up on becoming visible cheap.
            if (visible) loadMessages()
            scheduleRefresh()
        }, 3000)
    }

    /** Reload immediately when the sidebar is opened, rather than waiting out the tick. */
    private fun trackVisibility() {
        visible = isShowing
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) return@addHierarchyListener
            val nowVisible = isShowing
            val became = nowVisible && !visible
            visible = nowVisible
            if (became) ApplicationManager.getApplication().executeOnPooledThread { loadMessages() }
        }
    }

    /**
     * Parse whatever has been appended to the transcript since the last call and append the
     * new user messages to the list.
     *
     * Synchronized because the alarm thread, the visibility listener, and construction can
     * all reach it; the tail state must not interleave.
     */
    @Synchronized
    private fun loadMessages() {
        val sessionId = sessionIdProvider() ?: return
        val cwd = workingDirProvider() ?: project.basePath ?: return
        val jsonlPath = ClaudePathEncoder.projectDirCandidates(cwd)
            .map { it.resolve("$sessionId.jsonl") }
            .firstOrNull { Files.exists(it) } ?: return

        // A different transcript (session linked or rebound) invalidates the tail state.
        if (jsonlPath != lastPath) {
            lastPath = jsonlPath
            tail.reset()
            entries.clear()
        }

        // Only repaint when the *list* changed: appended lines are mostly assistant/tool
        // records, and rebuilding the model on every one would churn the EDT for nothing.
        var changed = false
        try {
            tail.consume(
                jsonlPath,
                onRestart = { entries.clear(); changed = true },
                onLine = { line -> parseUserMessage(line)?.let { entries.add(it); changed = true } }
            )
        } catch (_: Exception) {
            return
        }
        if (!changed) return

        val snapshot = entries.toList()
        ApplicationManager.getApplication().invokeLater {
            listModel.clear()
            snapshot.forEach { listModel.addElement(it) }
        }
    }

    /** A user message from one transcript line, or null for any other record type. */
    private fun parseUserMessage(line: String): MessageEntry? {
        if (line.isBlank()) return null
        return try {
            val obj = gson.fromJson(line, JsonObject::class.java)
            if (obj.get("type")?.asString != "user") return null
            val text = extractText(obj) ?: return null
            // Use the first line as the search key for terminal buffer matching
            val firstLine = text.lineSequence().first().trim()
            if (firstLine.isEmpty()) null else MessageEntry(text, firstLine)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractText(obj: JsonObject): String? {
        val message = obj.getAsJsonObject("message") ?: return null
        val content = message.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> {
                content.asJsonArray
                    .firstOrNull { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                    ?.asJsonObject?.get("text")?.asString
            }
            else -> null
        }
    }

    private fun scrollToMessage(listIndex: Int) {
        val entry = listModel.getElementAt(listIndex)
        val searchKey = entry.searchKey.take(40)

        // Count how many entries before this one share the same search key
        var targetOccurrence = 1
        for (i in 0 until listIndex) {
            if (listModel.getElementAt(i).searchKey.take(40) == searchKey) {
                targetOccurrence++
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val textBuffer = widget.terminalTextBuffer
                var found = 0

                textBuffer.lock()
                var targetRow: Int? = null
                val historyLines: Int
                val screenLines: Int
                try {
                    historyLines = textBuffer.historyLinesCount
                    screenLines = textBuffer.screenLinesCount
                    for (row in -historyLines until screenLines) {
                        val lineText = textBuffer.getLine(row).getText().trim()
                        if (lineText.contains(searchKey)) {
                            found++
                            if (found == targetOccurrence) {
                                targetRow = row
                                break
                            }
                        }
                    }
                } finally {
                    textBuffer.unlock()
                }

                if (targetRow != null) {
                    ApplicationManager.getApplication().invokeLater {
                        doScroll(targetRow, historyLines)
                    }
                } else {
                    // Dump some buffer lines for debugging
                    val sampleLines = mutableListOf<String>()
                    textBuffer.lock()
                    try {
                        val h = textBuffer.historyLinesCount
                        val s = textBuffer.screenLinesCount
                        val start = -h
                        val end = minOf(start + 30, s)
                        for (row in start until end) {
                            val lt = textBuffer.getLine(row).getText()
                            sampleLines.add("  row=$row: [${lt.take(80)}]")
                        }
                    } finally {
                        textBuffer.unlock()
                    }
                    log.info("Message not found in terminal buffer: searchKey='$searchKey', historyLines=$historyLines, screenLines=$screenLines\nSample lines:\n${sampleLines.joinToString("\n")}")
                }
            } catch (e: Exception) {
                log.warn("Failed to search terminal buffer", e)
            }
        }
    }

    private fun doScroll(row: Int, historyLines: Int) {
        try {
            val scrollBar = findScrollBar(widget)
            if (scrollBar != null) {
                val target = row + historyLines
                scrollBar.value = target.coerceIn(scrollBar.minimum, scrollBar.maximum - scrollBar.visibleAmount)
            } else {
                log.warn("Could not find scrollbar in terminal widget")
            }
        } catch (e: Exception) {
            log.warn("Failed to scroll terminal", e)
        }
    }

    private fun findScrollBar(container: Container): JScrollBar? {
        for (comp in container.components) {
            if (comp is JScrollBar && comp.orientation == JScrollBar.VERTICAL) return comp
            if (comp is Container) {
                val found = findScrollBar(comp)
                if (found != null) return found
            }
        }
        return null
    }

    override fun dispose() {}

    private class MessageEntryRenderer : ColoredListCellRenderer<MessageEntry>() {
        override fun customizeCellRenderer(
            list: JList<out MessageEntry>,
            value: MessageEntry,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            icon = AllIcons.General.User
            val preview = value.text.lineSequence().first().let {
                if (it.length > 50) it.take(47) + "\u2026" else it
            }
            append(preview, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
