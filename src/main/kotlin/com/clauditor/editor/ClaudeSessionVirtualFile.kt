package com.clauditor.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFileSystem
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

    /** Stable key used for VFS URL resolution so tabs survive drag-and-drop. */
    val sessionKey: String = sessionId ?: forkFrom ?: newWorktreeName ?: "new-${System.nanoTime()}"

    var baseName: String = name
    var workingDir: String? = null
    var isWorktreeSession: Boolean = newWorktreeName != null
    var modelId: String? = null
    var modelName: String? = null
    var contextPercent: Double? = null
    var isThinking: Boolean = false
    var notifyState: String? = null
    var isExternallyOpen: Boolean = false

    init {
        isWritable = false
    }

    override fun getFileSystem(): VirtualFileSystem =
        ClaudeSessionFileSystem.getInstanceOrNull() ?: super.getFileSystem()

    override fun getPath(): String = sessionKey

    override fun getUrl(): String = "${ClaudeSessionFileSystem.PROTOCOL}://$sessionKey"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClaudeSessionVirtualFile) return false
        return sessionKey == other.sessionKey
    }

    override fun hashCode(): Int = sessionKey.hashCode()

    fun computeTabTitle(): String = when {
        isExternallyOpen -> "\u2197 $baseName"                    // ↗ external session
        notifyState == "permission_prompt" -> "$baseName \u26A0"  // ⚠ needs permission
        notifyState == "idle_prompt" -> "$baseName \u25CB"        // ○ waiting for input
        notifyState?.startsWith("tool:") == true -> "$baseName \u2699" // ⚙ tool in use
        notifyState == "compact" -> "$baseName \u21BB"            // ↻ compacting
        isThinking -> "$baseName \u25CF"                          // ● thinking
        else -> baseName
    }
}
