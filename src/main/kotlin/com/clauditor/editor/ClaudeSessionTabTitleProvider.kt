package com.clauditor.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ClaudeSessionTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        return (file as? ClaudeSessionVirtualFile)?.computeTabTitle()
    }

    // Without this the platform falls back to the file's path, which is the
    // immutable sessionKey — the worktree name for tabs born from "New worktree
    // session", a temp key for new sessions — and never reflects later linking.
    override fun getEditorTabTooltipText(project: Project, virtualFile: VirtualFile): String? {
        val file = virtualFile as? ClaudeSessionVirtualFile ?: return null
        val parts = mutableListOf<String>()
        file.sessionId?.let { parts.add("Session: $it") }
        if (file.isWorktreeSession) file.workingDir?.let { parts.add("Worktree: $it") }
        if (parts.isEmpty()) parts.add("Session not started yet")
        return parts.joinToString(" — ")
    }
}
