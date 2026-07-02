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
    val sessionKey: String = sessionId
        ?: if (forkFrom != null) "fork-$forkFrom-${System.nanoTime()}" else null
        ?: newWorktreeName
        ?: "new-${System.nanoTime()}"

    var baseName: String = name
    var workingDir: String? = null
    var isWorktreeSession: Boolean = newWorktreeName != null
    var modelId: String? = null
    var modelName: String? = null
    var contextPercent: Double? = null
    var isThinking: Boolean = false
    var isUnresponsive: Boolean = false
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

    /** Build a fresh file pointing at the same session, preserving the bindings the editor needs to reconstruct correctly. */
    fun copyForReopen(): ClaudeSessionVirtualFile = ClaudeSessionVirtualFile(baseName, sessionId).also {
        it.workingDir = workingDir
        it.isWorktreeSession = isWorktreeSession
    }

    // Status is rendered as a fixed-size icon badge (see ClaudeSessionIconProvider), NOT
    // baked into the title text — a text glyph has a variable advance width, so toggling
    // it resized the tab and reflowed the whole strip. Returning null means "idle": the
    // badge slot is reserved but drawn empty, so the tab width is identical in every state.
    fun statusGlyph(): String? = when {
        isExternallyOpen -> "↗"                      // ↗ external session
        isUnresponsive -> "⊘"                        // ⊘ unresponsive
        notifyState == "permission_prompt" -> "⚠"    // ⚠ needs permission
        notifyState == "idle_prompt" -> "○"          // ○ waiting for input
        notifyState?.startsWith("tool:") == true -> "⚙" // ⚙ tool in use
        notifyState == "compact" -> "↻"              // ↻ compacting
        isThinking -> "●"                            // ● thinking
        else -> null                                 // idle — no badge, slot stays reserved and empty
    }

    fun computeTabTitle(): String = baseName
}
