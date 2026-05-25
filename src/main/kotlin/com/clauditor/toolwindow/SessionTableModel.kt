package com.clauditor.toolwindow

import com.clauditor.model.SessionDisplay
import com.clauditor.model.SessionStatus
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel

class SessionTableModel(
    private val getStatus: (String) -> SessionStatus,
    showWorktreeColumn: Boolean = false
) : ListTableModel<SessionDisplay>() {
    init {
        val cols = mutableListOf<ColumnInfo<SessionDisplay, *>>(
            StatusColumnInfo(getStatus),
            NameColumnInfo()
        )
        if (showWorktreeColumn) cols.add(WorktreeColumnInfo())
        cols.add(LastUsedColumnInfo())
        cols.add(MessagesColumnInfo())
        columnInfos = cols.toTypedArray()
    }
}

private class StatusColumnInfo(
    private val getStatus: (String) -> SessionStatus
) : ColumnInfo<SessionDisplay, String>("") {
    override fun valueOf(item: SessionDisplay): String = when (getStatus(item.sessionId)) {
        SessionStatus.OPEN_IN_PLUGIN -> "●"   // ● filled circle
        SessionStatus.OPEN_EXTERNALLY -> "↗"   // ↗ external arrow
        SessionStatus.AVAILABLE -> "○"          // ○ empty circle
    }

    override fun getWidth(table: javax.swing.JTable): Int = 28

    override fun getRenderer(item: SessionDisplay?): javax.swing.table.TableCellRenderer {
        return javax.swing.table.TableCellRenderer { table, value, isSelected, _, _, _ ->
            javax.swing.JLabel(value?.toString() ?: "").apply {
                horizontalAlignment = javax.swing.SwingConstants.CENTER
                font = table.font
                isOpaque = true
                background = if (isSelected) table.selectionBackground else table.background
                foreground = when {
                    isSelected -> table.selectionForeground
                    item != null && getStatus(item.sessionId) == SessionStatus.OPEN_IN_PLUGIN ->
                        com.intellij.ui.JBColor.namedColor("Component.focusColor", com.intellij.ui.JBColor(0x3574F0, 0x548AF7))
                    item != null && getStatus(item.sessionId) == SessionStatus.OPEN_EXTERNALLY ->
                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                    else -> com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                }
            }
        }
    }
}

private class NameColumnInfo : ColumnInfo<SessionDisplay, String>("Name") {
    override fun valueOf(item: SessionDisplay): String = item.displayName

    override fun getRenderer(item: SessionDisplay?): javax.swing.table.TableCellRenderer {
        return object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): java.awt.Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                toolTipText = buildNameTooltip(item)
                return c
            }
        }
    }
}

private fun buildNameTooltip(item: SessionDisplay?): String? {
    if (item == null) return null
    return item.summary?.takeIf { it.isNotBlank() }
        ?: item.firstPrompt.takeIf { it.isNotBlank() }
}

private class WorktreeColumnInfo : ColumnInfo<SessionDisplay, String>("Worktree") {
    override fun valueOf(item: SessionDisplay): String = item.worktreeName ?: ""
}

private class LastUsedColumnInfo : ColumnInfo<SessionDisplay, Long>("Last Used") {
    override fun valueOf(item: SessionDisplay): Long = item.modified.toEpochMilli()

    override fun getRenderer(item: SessionDisplay?): javax.swing.table.TableCellRenderer {
        return object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): java.awt.Component {
                val ms = (value as? Long) ?: 0L
                val displayText = if (ms > 0) DateFormatUtil.formatPrettyDateTime(ms) else ""
                val c = super.getTableCellRendererComponent(table, displayText, isSelected, hasFocus, row, column)
                toolTipText = if (ms > 0) DateFormatUtil.formatDateTime(ms) else null
                return c
            }
        }
    }
}

private class MessagesColumnInfo : ColumnInfo<SessionDisplay, Int>("Msgs") {
    override fun valueOf(item: SessionDisplay): Int = item.messageCount
}
