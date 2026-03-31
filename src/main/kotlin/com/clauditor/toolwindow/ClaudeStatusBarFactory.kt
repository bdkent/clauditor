package com.clauditor.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.Icon

class ClaudeStatusBarFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudeStatusBarPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.icon = IconLoader.getIcon("/icons/status.svg", ClaudeStatusBarFactory::class.java)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        val toggleAction = object : AnAction(), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) {
                panel.toggleLayout()
            }

            override fun update(e: AnActionEvent) {
                if (panel.isVertical) {
                    e.presentation.text = "Switch to horizontal layout"
                    e.presentation.icon = ICON_VERTICAL
                } else {
                    e.presentation.text = "Switch to vertical layout"
                    e.presentation.icon = ICON_HORIZONTAL
                }
            }
        }
        toolWindow.setTitleActions(listOf(toggleAction))
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath != null

    companion object {
        private val ICON_VERTICAL: Icon = IconLoader.getIcon("/icons/layout-vertical.svg", ClaudeStatusBarFactory::class.java)
        private val ICON_HORIZONTAL: Icon = IconLoader.getIcon("/icons/layout-horizontal.svg", ClaudeStatusBarFactory::class.java)
    }
}
