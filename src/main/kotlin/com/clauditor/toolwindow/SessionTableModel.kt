package com.clauditor.toolwindow

import com.clauditor.model.SessionDisplay
import com.clauditor.model.SessionStatus
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
        cols.add(FirstPromptColumnInfo())
        cols.add(MessagesColumnInfo())
        columnInfos = cols.toTypedArray()
    }
}

private class StatusColumnInfo(
    private val getStatus: (String) -> SessionStatus
) : ColumnInfo<SessionDisplay, String>("") {
    override fun valueOf(item: SessionDisplay): String = when (getStatus(item.sessionId)) {
        SessionStatus.OPEN_IN_PLUGIN -> "\u25CF"   // ● filled circle
        SessionStatus.OPEN_EXTERNALLY -> "\u2197"   // ↗ external arrow
        SessionStatus.AVAILABLE -> "\u25CB"          // ○ empty circle
    }

    override fun getWidth(table: javax.swing.JTable): Int = 28

    override fun getRenderer(item: SessionDisplay?): javax.swing.table.TableCellRenderer {
        return javax.swing.table.TableCellRenderer { table, value, isSelected, _, row, _ ->
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
}

private class WorktreeColumnInfo : ColumnInfo<SessionDisplay, String>("Worktree") {
    override fun valueOf(item: SessionDisplay): String = item.worktreeName ?: ""
    override fun getComparator(): Comparator<SessionDisplay> = compareBy { it.worktreeName ?: "" }
}

private class FirstPromptColumnInfo : ColumnInfo<SessionDisplay, String>("First Prompt") {
    override fun valueOf(item: SessionDisplay): String {
        val prompt = item.firstPrompt
        return if (prompt.length > 80) prompt.take(80) + "..." else prompt
    }
}

private class MessagesColumnInfo : ColumnInfo<SessionDisplay, Int>("Msgs") {
    override fun valueOf(item: SessionDisplay): Int = item.messageCount

    override fun getComparator(): Comparator<SessionDisplay> = compareByDescending { it.messageCount }
}
