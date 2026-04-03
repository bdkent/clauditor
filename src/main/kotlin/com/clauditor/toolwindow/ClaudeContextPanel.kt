package com.clauditor.toolwindow

import com.clauditor.editor.ClaudeSessionEditor
import com.clauditor.editor.ClaudeSessionVirtualFile
import com.clauditor.model.ContextItem
import com.clauditor.model.ContextItemLevel
import com.clauditor.model.ContextItemType
import com.clauditor.services.ClaudeContextService
import com.clauditor.util.ClaudePathEncoder
import com.clauditor.util.ProcessHelper
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
    private val activeTypes = mutableSetOf(ContextItemType.RULE, ContextItemType.AGENT, ContextItemType.SKILL, ContextItemType.MEMORY)
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
        val reviewButton = JButton("Review Memory").apply {
            isFocusable = false
            toolTipText = "Suggest which memories should be promoted to rules"
            addActionListener { reviewMemory(this) }
        }
        val secondRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
            add(reviewButton)
        }
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(toolbar)
            add(secondRow)
        }
        add(topPanel, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        reload()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Rescan rules, agents, skills, and memories", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { reload() }
        })

        group.addSeparator()

        group.add(typeToggle("Rules", ContextItemType.RULE))
        group.add(typeToggle("Agents", ContextItemType.AGENT))
        group.add(typeToggle("Skills", ContextItemType.SKILL))
        group.add(typeToggle("Memory", ContextItemType.MEMORY))

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
        }.apply {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
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
            "Skills" to ContextItemType.SKILL,
            "Memory" to ContextItemType.MEMORY
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

    private fun reviewMemory(anchor: JComponent) {
        val basePath = project.basePath ?: return
        val memoryDir = ClaudePathEncoder.projectDir(basePath).resolve("memory")
        if (!Files.isDirectory(memoryDir)) return

        // Read all memory files
        val memoryContent = StringBuilder()
        try {
            Files.list(memoryDir).use { stream ->
                stream.filter { it.toString().endsWith(".md") && Files.isRegularFile(it) }
                    .forEach { file ->
                        memoryContent.append("--- ${file.fileName} ---\n")
                        memoryContent.append(Files.readString(file))
                        memoryContent.append("\n\n")
                    }
            }
        } catch (_: Exception) { return }

        if (memoryContent.isBlank()) return

        // Also read existing rules for context
        val rulesContent = StringBuilder()
        val rulesDirs = listOf(
            java.nio.file.Path.of(System.getProperty("user.home"), ".claude", "rules"),
            java.nio.file.Path.of(basePath, ".claude", "rules")
        )
        for (dir in rulesDirs) {
            if (!Files.isDirectory(dir)) continue
            try {
                Files.list(dir).use { stream ->
                    stream.filter { it.toString().endsWith(".md") && Files.isRegularFile(it) }
                        .forEach { file ->
                            rulesContent.append("--- ${file.fileName} ---\n")
                            rulesContent.append(Files.readString(file))
                            rulesContent.append("\n\n")
                        }
                }
            } catch (_: Exception) {}
        }

        val prompt = buildString {
            append("Review these auto-memory files and suggest which ones should be promoted to proper rules (in .claude/rules/) for wider, more reliable use. ")
            append("Also flag any that seem stale, redundant, or no longer useful. ")
            append("Output ONLY the review in markdown — no preamble, no follow-up questions.\n\n")
            append("## Current Memories\n\n$memoryContent\n")
            if (rulesContent.isNotBlank()) {
                append("## Existing Rules (for reference, to avoid duplicates)\n\n$rulesContent\n")
            }
        }

        runStandaloneQuery(anchor, prompt)
    }

    private fun runStandaloneQuery(anchor: JComponent, prompt: String) {
        val workingDir = project.basePath ?: return

        val balloon = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createBalloonBuilder(JBLabel("  Thinking\u2026  "))
            .setFadeoutTime(0)
            .setAnimationCycle(0)
            .setHideOnClickOutside(false)
            .setHideOnAction(false)
            .setFillColor(UIManager.getColor("Panel.background") ?: Color(60, 63, 65))
            .createBalloon()
        balloon.show(
            com.intellij.ui.awt.RelativePoint.getSouthOf(anchor),
            com.intellij.openapi.ui.popup.Balloon.Position.below
        )

        AppExecutorUtil.getAppScheduledExecutorService().execute {
            try {
                val claudeBin = ProcessHelper.which("claude") ?: "claude"
                val process = ProcessBuilder(
                    claudeBin, "-p",
                    "--no-session-persistence",
                    "--model", "sonnet",
                    "--append-system-prompt", "You are answering a query from a plugin UI popup. Respond ONLY with the requested content. No conversational filler, no follow-up questions, no commentary.",
                    prompt
                ).apply {
                    environment().putAll(ProcessHelper.augmentedEnv())
                    directory(java.io.File(workingDir))
                    redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    redirectErrorStream(true)
                }.start()

                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(120, TimeUnit.SECONDS)
                val result = output.trim()

                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (project.isDisposed) return@invokeLater
                    showPopupResult(anchor, "Memory Review", result)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    if (!project.isDisposed) {
                        showPopupResult(anchor, "Memory Review", "Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showPopupResult(anchor: JComponent, title: String, text: String) {
        val extensions = listOf(org.commonmark.ext.gfm.tables.TablesExtension.create())
        val parser = org.commonmark.parser.Parser.builder().extensions(extensions).build()
        val renderer = org.commonmark.renderer.html.HtmlRenderer.builder().extensions(extensions).build()
        val html = renderer.render(parser.parse(text))

        val styleConfig = com.intellij.ui.components.JBHtmlPaneStyleConfiguration.builder().apply {
            enableInlineCodeBackground = true
            enableCodeBlocksBackground = true
        }.build()
        val htmlPane = com.intellij.ui.components.JBHtmlPane(styleConfig, com.intellij.ui.components.JBHtmlPaneConfiguration()).apply {
            this.text = html
            isEditable = false
            border = JBUI.Borders.empty(8)
        }
        Disposer.register(project, htmlPane)

        val scrollPane = JScrollPane(htmlPane).apply {
            preferredSize = java.awt.Dimension(550, 400)
            border = null
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, htmlPane)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(anchor)
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
