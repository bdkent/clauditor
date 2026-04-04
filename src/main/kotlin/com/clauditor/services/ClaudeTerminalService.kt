package com.clauditor.services

import com.clauditor.terminal.ActivityMonitoringTtyConnector
import com.clauditor.terminal.FilteringPtyConnector
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class TerminalSession(val widget: JBTerminalWidget, val process: PtyProcess)

@Service(Service.Level.PROJECT)
class ClaudeTerminalService(private val project: Project) {

    private val log = Logger.getInstance(ClaudeTerminalService::class.java)

    fun createResumeWidget(
        sessionId: String,
        parent: Disposable,
        workingDir: String? = null,
        statusFile: Path? = null,
        notifyFile: Path? = null,
        onActiveChanged: ((Boolean) -> Unit)? = null,
        onUserInput: (() -> Unit)? = null,
        onUnresponsive: (() -> Unit)? = null
    ): TerminalSession {
        return createWidget(
            arrayOf("claude", "--resume", sessionId),
            parent, workingDir = workingDir, statusFile = statusFile, notifyFile = notifyFile,
            onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive
        )
    }

    fun createForkWidget(
        forkFromSessionId: String,
        parent: Disposable,
        workingDir: String? = null,
        statusFile: Path? = null,
        notifyFile: Path? = null,
        onActiveChanged: ((Boolean) -> Unit)? = null,
        onUserInput: (() -> Unit)? = null,
        onUnresponsive: (() -> Unit)? = null
    ): TerminalSession {
        return createWidget(
            arrayOf("claude", "--resume", forkFromSessionId, "--fork-session"),
            parent, workingDir = workingDir, statusFile = statusFile, notifyFile = notifyFile,
            onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive
        )
    }

    fun createNewWorktreeWidget(
        worktreeName: String,
        parent: Disposable,
        statusFile: Path? = null,
        notifyFile: Path? = null,
        onActiveChanged: ((Boolean) -> Unit)? = null,
        onUserInput: (() -> Unit)? = null,
        onUnresponsive: (() -> Unit)? = null
    ): TerminalSession {
        return createWidget(
            arrayOf("claude", "--worktree", worktreeName),
            parent, statusFile = statusFile, notifyFile = notifyFile,
            onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive
        )
    }

    fun createNewNamedSessionWidget(
        name: String,
        parent: Disposable,
        workingDir: String? = null,
        statusFile: Path? = null,
        notifyFile: Path? = null,
        onActiveChanged: ((Boolean) -> Unit)? = null,
        onUserInput: (() -> Unit)? = null,
        onUnresponsive: (() -> Unit)? = null
    ): TerminalSession {
        return createWidget(arrayOf("claude", "--name", name), parent, workingDir = workingDir, statusFile = statusFile, notifyFile = notifyFile,
            onActiveChanged = onActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
    }

    private fun createWidget(
        command: Array<String>,
        parent: Disposable,
        workingDir: String? = null,
        statusFile: Path? = null,
        notifyFile: Path? = null,
        onReady: ((PtyProcess) -> Unit)? = null,
        onActiveChanged: ((Boolean) -> Unit)? = null,
        onUserInput: (() -> Unit)? = null,
        onUnresponsive: (() -> Unit)? = null
    ): TerminalSession {
        val env = com.clauditor.util.ProcessHelper.augmentedEnv()
        env["TERM"] = "xterm-256color"

        val effectiveWorkingDir = workingDir ?: project.basePath ?: System.getProperty("user.home")

        log.info("Clauditor: createWidget — command=${command.toList()}, workingDir=$effectiveWorkingDir")
        val claudePath = com.clauditor.util.ProcessHelper.which(command[0])
        log.info("Clauditor: createWidget — '${command[0]}' resolved to: $claudePath")

        // Build the full command, adding --settings override for status interception
        val fullCommand = if (statusFile != null) {
            val statusService = ClaudeStatusService.getInstance(project)
            val wrapperPath = statusService.getWrapperScriptPath()
            val notifyScriptPath = statusService.getNotifyScriptPath()
            val overridePath = statusService.createOverrideSettingsFile(wrapperPath, notifyScriptPath)

            env["CLAUDITOR_STATUS_FILE"] = statusFile.toAbsolutePath().toString()
            env["CLAUDITOR_NOTIFY_FILE"] = notifyFile?.toAbsolutePath()?.toString() ?: ""
            env["CLAUDITOR_ORIGINAL_STATUSLINE"] = statusService.discoverOriginalStatusLineCommand() ?: ""

            Disposer.register(parent, Disposable {
                try { Files.deleteIfExists(overridePath) } catch (_: Exception) {}
            })

            // Insert --settings right after "claude"
            arrayOf(command[0], "--settings", overridePath.toAbsolutePath().toString()) +
                command.drop(1).toTypedArray()
        } else {
            command
        }

        log.info("Clauditor: createWidget — fullCommand=${fullCommand.toList()}, PATH=${env["PATH"]?.take(200)}")

        val ptyProcess = try {
            PtyProcessBuilder(fullCommand)
                .setEnvironment(env)
                .setDirectory(effectiveWorkingDir)
                .start()
        } catch (e: Exception) {
            log.error("Clauditor: createWidget — failed to start PTY process: ${fullCommand.toList()}", e)
            throw e
        }

        // Wrap onActiveChanged to fire onReady on the first idle transition
        val wrappedOnActiveChanged: ((Boolean) -> Unit)? = if (onActiveChanged != null || onReady != null) {
            var readyFired = false
            { active: Boolean ->
                if (!active && !readyFired && onReady != null) {
                    readyFired = true
                    onReady(ptyProcess)
                }
                onActiveChanged?.invoke(active)
            }
        } else null

        val connector = if (wrappedOnActiveChanged != null) {
            ActivityMonitoringTtyConnector(ptyProcess, StandardCharsets.UTF_8, onActiveChanged = wrappedOnActiveChanged, onUserInput = onUserInput, onUnresponsive = onUnresponsive)
        } else {
            FilteringPtyConnector(ptyProcess, StandardCharsets.UTF_8)
        }

        val settingsProvider = JBTerminalSystemSettingsProvider()
        val widget = JBTerminalWidget(project, settingsProvider, parent)
        widget.start(connector)

        Disposer.register(parent, Disposable {
            if (ptyProcess.isAlive) {
                ptyProcess.destroy()
                // Force kill if it doesn't exit promptly (e.g. during tab detach)
                if (!ptyProcess.waitFor(2, TimeUnit.SECONDS)) {
                    ptyProcess.destroyForcibly()
                }
            }
        })

        return TerminalSession(widget, ptyProcess)
    }

    private fun sendInput(process: PtyProcess, text: String) {
        if (!process.isAlive) return
        try {
            process.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        } catch (e: Exception) {
            log.warn("Failed to send input to claude process", e)
        }
    }

    companion object {
        fun getInstance(project: Project): ClaudeTerminalService =
            project.getService(ClaudeTerminalService::class.java)
    }
}
