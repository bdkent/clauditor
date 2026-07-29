package com.clauditor.toolwindow

import com.clauditor.editor.ClaudeSessionEditor
import com.clauditor.editor.ClaudeSessionVirtualFile
import com.clauditor.model.ScratchpadFile
import com.clauditor.model.ScratchpadGroup
import com.clauditor.services.ScratchpadService
import com.clauditor.util.ClauditorExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Browser for Claude Code's per-session scratchpad directories.
 *
 * Sibling tab to [ClaudeContextPanel]. Kept separate because scratchpad data shares none of
 * the context tree's shape: it is session-scoped rather than personal/project, its files carry
 * no frontmatter to describe them, and it can hold hundreds of arbitrary artifacts.
 *
 * Scanning is gated on the tab actually being on screen (see [setTabVisible]) so an unselected
 * tab never walks these directories.
 */
class ClaudeScratchpadPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val emptyLabel = JLabel(NO_FILES_TEXT, SwingConstants.CENTER)
    private val scrollPane = ScrollPaneFactory.createScrollPane(tree)

    private var groups: List<ScratchpadGroup> = emptyList()
    private var currentSessionOnly = false

    private val inFlight = AtomicBoolean(false)
    @Volatile private var tabVisible = false
    @Volatile private var stale = true

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ScratchpadTreeRenderer()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedFile()?.let { openFile(it) }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getClosestPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val obj = node.userObject) {
                    is ScratchpadFile -> filePopup(obj).show(tree, e.x, e.y)
                    is ScratchpadGroup -> groupPopup(obj).show(tree, e.x, e.y)
                }
            }
        })

        add(createToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Follow the focused session tab so "this session" stays accurate.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    markStale()
                }
            }
        )

        // A tool-window tab that is switched away from stops being showing().
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                setTabVisible(isShowing)
            }
        }
    }

    /** Called by the tool window when this tab is selected or deselected. */
    fun setTabVisible(visible: Boolean) {
        if (tabVisible == visible) return
        tabVisible = visible
        if (visible && stale) reload()
    }

    private fun markStale() {
        stale = true
        if (tabVisible) reload()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Rescan session scratchpads", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = reload()
        })

        group.addSeparator()

        group.add(object : ToggleAction("This Session", "Show only the focused session's scratchpad", null) {
            override fun isSelected(e: AnActionEvent) = currentSessionOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                currentSessionOnly = state
                rebuildTree()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }.apply {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudeScratchpadToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun reload() {
        if (!inFlight.compareAndSet(false, true)) return
        stale = false
        val currentId = focusedSessionId()
        ClauditorExecutor.submit {
            val scanned = try {
                ScratchpadService.getInstance(project).scan(currentId)
            } catch (_: Exception) {
                emptyList()
            } finally {
                inFlight.set(false)
            }
            ApplicationManager.getApplication().invokeLater {
                groups = scanned
                rebuildTree()
            }
        }
    }

    private fun focusedSessionId(): String? =
        (FileEditorManager.getInstance(project).selectedFiles
            .firstOrNull { it is ClaudeSessionVirtualFile } as? ClaudeSessionVirtualFile)?.sessionId

    private fun rebuildTree() {
        val expanded = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val g = node.userObject as? ScratchpadGroup ?: continue
            if (tree.isExpanded(TreePath(node.path))) expanded.add(g.sessionId)
        }
        val firstBuild = rootNode.childCount == 0

        rootNode.removeAllChildren()

        val visible = if (currentSessionOnly) groups.filter { it.isCurrent } else groups
        for (g in visible) {
            val groupNode = DefaultMutableTreeNode(g)
            g.files.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
            rootNode.add(groupNode)
        }

        treeModel.reload()

        for (i in 0 until rootNode.childCount) {
            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val g = node.userObject as? ScratchpadGroup ?: continue
            // Expand the current session by default; keep whatever else the user had open.
            if (g.sessionId in expanded || (firstBuild && g.isCurrent) || (firstBuild && visible.size == 1)) {
                tree.expandPath(TreePath(node.path))
            }
        }

        val empty = visible.isEmpty()
        emptyLabel.text = if (currentSessionOnly && groups.isNotEmpty()) NO_CURRENT_TEXT else NO_FILES_TEXT
        scrollPane.setViewportView(if (empty) emptyLabel else tree)
    }

    private fun selectedFile(): ScratchpadFile? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ScratchpadFile

    private fun filePopup(file: ScratchpadFile): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Open File").apply { addActionListener { openFile(file) } })
        add(JMenuItem("Copy Path").apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(file.path.toString())) }
        })
        add(JMenuItem("Insert Path into Terminal").apply {
            addActionListener { insertIntoTerminal(file.path.toString()) }
        })
        add(JMenuItem("Reveal in ${com.intellij.ide.actions.RevealFileAction.getFileManagerName()}").apply {
            addActionListener { com.intellij.ide.actions.RevealFileAction.openFile(file.path.toFile()) }
        })
    }

    private fun groupPopup(group: ScratchpadGroup): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Open Folder").apply {
            addActionListener { com.intellij.ide.actions.RevealFileAction.openDirectory(group.dir.toFile()) }
        })
        add(JMenuItem("Copy Path").apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(group.dir.toString())) }
        })
    }

    private fun openFile(file: ScratchpadFile) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun insertIntoTerminal(text: String) {
        val manager = FileEditorManager.getInstance(project)
        val activeFile = manager.selectedFiles
            .firstOrNull { it is ClaudeSessionVirtualFile } as? ClaudeSessionVirtualFile ?: return
        val editor = manager.getEditors(activeFile)
            .filterIsInstance<ClaudeSessionEditor>()
            .firstOrNull() ?: return
        editor.sendToTerminal("$text ")
    }

    override fun dispose() {}

    private class ScratchpadTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is ScratchpadGroup -> {
                    icon = AllIcons.Nodes.Folder
                    val title = if (obj.isCurrent) "This session" else obj.label
                    append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val count = if (obj.truncated) "${obj.files.size}+" else "${obj.files.size}"
                    append("  ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ScratchpadFile -> {
                    icon = AllIcons.FileTypes.Any_type
                    append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  — ${formatSize(obj.size)} · ${formatTime(obj.modified)}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is String -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        private const val NO_FILES_TEXT = "No scratchpad files for this project's sessions"
        private const val NO_CURRENT_TEXT = "The focused session has no scratchpad files"

        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())

        fun formatTime(instant: java.time.Instant): String = TIME_FMT.format(instant)

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
