package com.clauditor.toolwindow

import com.clauditor.model.SessionDisplay
import com.clauditor.model.SessionStatus
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel

class SessionTableModel(
    private val getStatus: (String) -> SessionStatus,
    showWorktreeColumn: Boolean = false,
    getWorktreeStatus: ((String) -> WorktreeStatus?)? = null
) : ListTableModel<SessionDisplay>() {
    init {
        val cols = mutableListOf<ColumnInfo<SessionDisplay, *>>(
            StatusColumnInfo(getStatus),
            NameColumnInfo()
        )
        if (showWorktreeColumn) cols.add(WorktreeColumnInfo())
        if (getWorktreeStatus != null) cols.add(GitColumnInfo(getWorktreeStatus))
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

private fun buildNameTooltip(item: SessionDisplay?): String? =
    item?.firstPrompt?.takeIf { it.isNotBlank() }

private class WorktreeColumnInfo : ColumnInfo<SessionDisplay, String>("Worktree") {
    override fun valueOf(item: SessionDisplay): String = item.worktreeName ?: ""
}

/**
 * Uncommitted/unmerged state of the row's worktree: "✎3" for 3 uncommitted files, "↑2" for
 * 2 commits not in the base branch, blank when there's nothing outstanding — so a row that
 * needs attention is the only thing that draws the eye.
 *
 * Sorts on a numeric rank (uncommitted dominates) rather than the display string, so one
 * click puts the worktrees you forgot about on top. The renderer reads a cache that a
 * background sweep fills in; cells stay blank until the first sweep lands.
 */
private class GitColumnInfo(
    private val getWorktreeStatus: (String) -> WorktreeStatus?
) : ColumnInfo<SessionDisplay, Int>("Git") {

    override fun getColumnClass(): Class<*> = java.lang.Integer::class.java

    override fun valueOf(item: SessionDisplay): Int {
        val st = statusOf(item) ?: return -1
        // Uncommitted work outranks unmerged commits; the cap keeps a pathological commit
        // count from spilling into the next dirty-file bucket.
        return st.dirtyCount * 100_000 + st.ahead.coerceAtMost(99_999)
    }

    override fun getWidth(table: javax.swing.JTable): Int = 72

    private fun statusOf(item: SessionDisplay): WorktreeStatus? =
        item.worktreeName?.let { getWorktreeStatus(it) }

    override fun getRenderer(item: SessionDisplay?): javax.swing.table.TableCellRenderer {
        return javax.swing.table.TableCellRenderer { table, _, isSelected, _, _, _ ->
            val st = item?.let { statusOf(it) }
            javax.swing.JLabel(text(st)).apply {
                font = table.font
                isOpaque = true
                background = if (isSelected) table.selectionBackground else table.background
                foreground = when {
                    isSelected -> table.selectionForeground
                    st == null -> com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                    // Red is reserved for a git operation frozen mid-flight. A missing or
                    // unreadable worktree stays muted — a worktree the CLI is still creating
                    // reads as MISSING for a moment and shouldn't flash an alarm.
                    st.state in INTERRUPTED_STATES -> com.intellij.ui.JBColor.RED
                    // Uncommitted work is the thing worth noticing; unmerged commits alone
                    // are a normal in-progress state, so they stay in the muted colour.
                    st.dirty -> DIRTY_COLOR
                    else -> com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                }
                toolTipText = tooltip(st)
            }
        }
    }

    private fun text(st: WorktreeStatus?): String = when {
        st == null -> ""
        st.state == WorktreeState.REBASING -> "rebase"
        st.state == WorktreeState.MERGING -> "merge"
        st.state == WorktreeState.CHERRY_PICKING -> "pick"
        st.state == WorktreeState.DETACHED -> "detached"
        st.state == WorktreeState.MISSING -> "—"
        st.state == WorktreeState.UNKNOWN -> "?"
        else -> buildString {
            if (st.dirtyCount > 0) append("✎${st.dirtyCount}")
            if (st.ahead > 0) {
                if (isNotEmpty()) append(" ")
                append("↑${st.ahead}")
            }
        }
    }

    private fun tooltip(st: WorktreeStatus?): String? {
        if (st == null) return null
        val base = st.mainBranch ?: "the project branch"
        val lines = mutableListOf<String>()
        when (st.state) {
            WorktreeState.REBASING -> lines += "Rebase in progress"
            WorktreeState.MERGING -> lines += "Merge in progress"
            WorktreeState.CHERRY_PICKING -> lines += "Cherry-pick in progress"
            WorktreeState.DETACHED -> lines += "Detached HEAD"
            WorktreeState.MISSING -> return "Worktree directory not found"
            WorktreeState.UNKNOWN -> return "Status unavailable: ${st.errorDetail ?: "unknown error"}"
            WorktreeState.OK -> {}
        }
        if (st.dirtyCount > 0) {
            lines += "${st.dirtyCount} uncommitted file${if (st.dirtyCount > 1) "s" else ""}:"
            lines += st.dirtyFiles.take(10).map { " $it" }
            if (st.dirtyCount > 10) lines += " … and ${st.dirtyCount - 10} more"
        }
        if (st.ahead > 0) {
            lines += "${st.ahead} commit${if (st.ahead > 1) "s" else ""} not in $base"
        }
        if (st.behind > 0) {
            lines += "${st.behind} commit${if (st.behind > 1) "s" else ""} behind $base"
        }
        if (lines.isEmpty()) {
            lines += if (st.ahead == 0 && st.behind == 0) "Clean, level with $base"
                     else "Clean — nothing to merge into $base"
        }
        return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
    }

    private companion object {
        val DIRTY_COLOR = com.intellij.ui.JBColor(0xB8730E, 0xD9A343)
        val INTERRUPTED_STATES = setOf(
            WorktreeState.REBASING, WorktreeState.MERGING, WorktreeState.CHERRY_PICKING
        )
    }
}

private class LastUsedColumnInfo : ColumnInfo<SessionDisplay, Long>("Last Used") {
    // Declare the real value type so TableRowSorter uses natural (chronological) ordering.
    // Without this, ColumnInfo's default String class makes the sorter pick a Collator,
    // which throws ClassCastException on the Long value and silently aborts the sort.
    override fun getColumnClass(): Class<*> = java.lang.Long::class.java

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
    // Declare Integer so TableRowSorter sorts numerically (Collator would CCE on Int). As a
    // side effect JTable's default Number renderer right-aligns the counts, which is fine.
    override fun getColumnClass(): Class<*> = java.lang.Integer::class.java

    override fun valueOf(item: SessionDisplay): Int = item.messageCount
}
