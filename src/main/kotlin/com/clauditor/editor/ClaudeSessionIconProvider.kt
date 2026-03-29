package com.clauditor.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.RowIcon
import javax.swing.Icon

class ClaudeSessionIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file !is ClaudeSessionVirtualFile) return null
        if (!file.isWorktreeSession) return null
        return WORKTREE_ICON
    }

    companion object {
        private val CLAUDE_ICON = IconLoader.getIcon("/icons/claude.svg", ClaudeSessionIconProvider::class.java)
        val TREE_ICON: Icon = IconLoader.getIcon("/icons/worktree.svg", ClaudeSessionIconProvider::class.java)
        private val WORKTREE_ICON = RowIcon(CLAUDE_ICON, TREE_ICON)
    }
}
