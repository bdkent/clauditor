package com.clauditor.toolwindow

import com.clauditor.editor.ClaudeSessionEditor
import com.clauditor.editor.ClaudeSessionVirtualFile
import com.clauditor.model.ContextItem
import com.clauditor.model.ContextItemLevel
import com.clauditor.model.ContextItemType
import com.clauditor.services.ClaudeContextService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ClaudeContextPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private var allItems: List<ContextItem> = emptyList()
    private val activeTypes = mutableSetOf(ContextItemType.RULE, ContextItemType.AGENT, ContextItemType.SKILL)
    private val activeLevels = mutableSetOf(ContextItemLevel.PERSONAL, ContextItemLevel.PROJECT)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ContextTreeRenderer()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }

            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getClosestPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val item = node.userObject as? ContextItem ?: return
                createPopupMenu(item).show(tree, e.x, e.y)
            }
        })

        val toolbar = createToolbar()
        add(toolbar, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        reload()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Rescan rules, agents, and skills", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { reload() }
        })

        group.addSeparator()

        group.add(typeToggle("Rules", ContextItemType.RULE))
        group.add(typeToggle("Agents", ContextItemType.AGENT))
        group.add(typeToggle("Skills", ContextItemType.SKILL))

        group.addSeparator()

        group.add(levelToggle("Personal (~/.claude)", ContextItemLevel.PERSONAL, AllIcons.General.User))
        group.add(levelToggle("Project (.claude)", ContextItemLevel.PROJECT, AllIcons.Nodes.Module))

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudeContextToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun typeToggle(label: String, type: ContextItemType) =
        object : ToggleAction(label, "Toggle $label", null) {
            override fun isSelected(e: AnActionEvent) = type in activeTypes
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) activeTypes.add(type) else activeTypes.remove(type)
                rebuildTree()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun displayTextInToolbar() = true
        }

    private fun levelToggle(tooltip: String, level: ContextItemLevel, icon: Icon) =
        object : ToggleAction(tooltip, tooltip, icon) {
            override fun isSelected(e: AnActionEvent) = level in activeLevels
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) activeLevels.add(level) else activeLevels.remove(level)
                rebuildTree()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = ClaudeContextService.getInstance(project).scan()
            ApplicationManager.getApplication().invokeLater {
                allItems = items
                rebuildTree()
            }
        }
    }

    private fun rebuildTree() {
        // Remember which sections were expanded
        val expanded = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val sectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            if (tree.isExpanded(TreePath(sectionNode.path))) {
                expanded.add(sectionNode.userObject as String)
            }
        }
        val firstBuild = rootNode.childCount == 0

        rootNode.removeAllChildren()

        val filtered = allItems
            .filter { it.type in activeTypes }
            .filter { it.level in activeLevels }

        val sections = listOf(
            "Rules" to ContextItemType.RULE,
            "Agents" to ContextItemType.AGENT,
            "Skills" to ContextItemType.SKILL
        )

        for ((label, type) in sections) {
            val items = filtered.filter { it.type == type }
            if (items.isEmpty()) continue
            val sectionNode = DefaultMutableTreeNode("$label (${items.size})")
            items.forEach { sectionNode.add(DefaultMutableTreeNode(it)) }
            rootNode.add(sectionNode)
        }

        treeModel.reload()

        // Restore expansion state, or expand all on first load
        for (i in 0 until rootNode.childCount) {
            val sectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val label = (sectionNode.userObject as String).substringBefore(" (")
            if (firstBuild || label in expanded.map { it.substringBefore(" (") }) {
                tree.expandPath(TreePath(sectionNode.path))
            }
        }
    }

    private fun createPopupMenu(item: ContextItem): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Open File").apply {
            addActionListener { openItem(item) }
        })
        if (item.insertText.isNotEmpty()) {
            menu.add(JMenuItem("Insert into Terminal").apply {
                addActionListener { insertItem(item) }
            })
        }
        return menu
    }

    private fun openSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? ContextItem ?: return
        openItem(item)
    }

    private fun openItem(item: ContextItem) {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(item.path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun insertItem(item: ContextItem) {
        val text = item.insertText
        if (text.isEmpty()) return

        val manager = FileEditorManager.getInstance(project)
        val activeFile = manager.selectedFiles.firstOrNull { it is ClaudeSessionVirtualFile } as? ClaudeSessionVirtualFile
            ?: return
        val editor = manager.getEditors(activeFile)
            .filterIsInstance<ClaudeSessionEditor>()
            .firstOrNull() ?: return
        editor.sendToTerminal(text)
    }

    private class ContextTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is ContextItem -> {
                    icon = when (obj.level) {
                        ContextItemLevel.PERSONAL -> AllIcons.General.User
                        ContextItemLevel.PROJECT -> AllIcons.Nodes.Module
                    }
                    append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    obj.description?.let {
                        val short = if (it.length > 60) it.take(57) + "\u2026" else it
                        append("  \u2014 $short", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is String -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }
}
