package com.clauditor.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ClaudeContextToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val icon = IconLoader.getIcon("/icons/context.svg", ClaudeContextToolWindowFactory::class.java)

        val contextPanel = ClaudeContextPanel(project)
        val contextContent = ContentFactory.getInstance().createContent(contextPanel, "Context", false)
        contextContent.icon = icon
        contextContent.isCloseable = false
        toolWindow.contentManager.addContent(contextContent)

        val scratchpadPanel = ClaudeScratchpadPanel(project)
        val scratchpadContent = ContentFactory.getInstance().createContent(scratchpadPanel, "Scratchpad", false)
        scratchpadContent.icon = icon
        scratchpadContent.isCloseable = false
        scratchpadContent.setDisposer(scratchpadPanel)
        toolWindow.contentManager.addContent(scratchpadContent)
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath != null
}
