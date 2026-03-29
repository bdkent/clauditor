package com.clauditor.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.LightVirtualFile
import javax.swing.Icon

private object ClaudeSessionFileType : FileType {
    private val ICON = IconLoader.getIcon("/icons/claude.svg", ClaudeSessionFileType::class.java)

    override fun getName(): String = "Claude Session"
    override fun getDefaultExtension(): String = ""
    override fun getDescription(): String = "Claude Code Session"
    override fun getIcon(): Icon = ICON
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

class ClaudeSessionVirtualFile(
    name: String,
    var sessionId: String? = null,
    val forkFrom: String? = null,
    val newWorktreeName: String? = null
) : LightVirtualFile(name, ClaudeSessionFileType, "") {

    var baseName: String = name
    var workingDir: String? = null
    var isWorktreeSession: Boolean = newWorktreeName != null
    var modelId: String? = null
    var modelName: String? = null
    var contextPercent: Double? = null
    var isThinking: Boolean = false
    var notifyState: String? = null

    init {
        isWritable = false
    }

    fun computeTabTitle(): String = when {
        notifyState == "permission_prompt" -> "$baseName \u26A0"  // ⚠ needs permission
        notifyState == "idle_prompt" -> "$baseName \u25CB"        // ○ waiting for input
        notifyState?.startsWith("tool:") == true -> "$baseName \u2699" // ⚙ tool in use
        notifyState == "compact" -> "$baseName \u21BB"            // ↻ compacting
        isThinking -> "$baseName \u25CF"                          // ● thinking
        else -> baseName
    }
}
