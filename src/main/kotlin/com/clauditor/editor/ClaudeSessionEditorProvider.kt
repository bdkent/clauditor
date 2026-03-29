package com.clauditor.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ClaudeSessionEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is ClaudeSessionVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return ClaudeSessionEditor(project, file as ClaudeSessionVirtualFile)
    }

    override fun getEditorTypeId(): String = "clauditor-session"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
