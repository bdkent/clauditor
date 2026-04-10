package com.clauditor.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.pty4j.PtyProcess
import java.nio.charset.Charset
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Extends [FilteringPtyConnector] so that unsupported escape sequences are stripped
 * before activity detection runs, and all resize-related interface implementations
 * are inherited without modification.
 *
 * Fires [onActiveChanged](true) when Claude writes output, and [onActiveChanged](false)
 * once output has been quiet for [idleTimeoutMs].
 *
 * User keystrokes cause PTY echo that arrives via [read] within ~50ms. We suppress the
 * indicator during a [echoWindowMs] window after every [write] so that typing doesn't
 * light up the "thinking" indicator.
 *
 * Callbacks are always invoked on the EDT.
 */
class ActivityMonitoringTtyConnector(
    process: PtyProcess,
    charset: Charset,
    private val idleTimeoutMs: Long = 1200,
    private val echoWindowMs: Long = 500,
    private val echoTimeoutMs: Long = 3000,
    private val onActiveChanged: (active: Boolean) -> Unit,
    private val onUserInput: (() -> Unit)? = null,
    private val onUnresponsive: (() -> Unit)? = null
) : FilteringPtyConnector(process, charset) {

    private val log = Logger.getInstance(ActivityMonitoringTtyConnector::class.java)
    private val ptyProcess = process

    @Volatile private var active = false
    @Volatile private var lastWriteMs: Long = 0
    @Volatile private var lastReadMs: Long = 0
    @Volatile private var lastReadCount: Int = 0
    @Volatile private var totalReads: Long = 0
    @Volatile private var totalReadChars: Long = 0
    @Volatile private var readThreadName: String? = null
    @Volatile private var readBlocked = false
    @Volatile private var unresponsiveNotified = false
    private var idleFuture: ScheduledFuture<*>? = null
    private var echoCheckFuture: ScheduledFuture<*>? = null

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        readThreadName = Thread.currentThread().name
        readBlocked = true
        val count = super.read(buf, offset, length)
        readBlocked = false
        if (count > 0) {
            lastReadMs = System.currentTimeMillis()
            lastReadCount = count
            totalReads++
            totalReadChars += count
            if (unresponsiveNotified) {
                unresponsiveNotified = false
            }
            if (System.currentTimeMillis() - lastWriteMs > echoWindowMs) {
                if (!active) {
                    active = true
                    ApplicationManager.getApplication().invokeLater { onActiveChanged(true) }
                }
                idleFuture?.cancel(false)
                idleFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    active = false
                    ApplicationManager.getApplication().invokeLater { onActiveChanged(false) }
                }, idleTimeoutMs, TimeUnit.MILLISECONDS)
            }
        }
        return count
    }

    override fun write(bytes: ByteArray) {
        super.write(bytes)
        scheduleEchoCheck()
        onUserInput?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
    }

    override fun write(string: String) {
        super.write(string)
        scheduleEchoCheck()
        onUserInput?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
    }


    private fun scheduleEchoCheck() {
        lastWriteMs = System.currentTimeMillis()
        unresponsiveNotified = false
        echoCheckFuture?.cancel(false)
        echoCheckFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!unresponsiveNotified && lastReadMs < lastWriteMs) {
                unresponsiveNotified = true
                logUnresponsiveDiagnostics()
                onUnresponsive?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            }
        }, echoTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun logUnresponsiveDiagnostics() {
        val now = System.currentTimeMillis()
        val processAlive = try { ptyProcess.isAlive } catch (_: Exception) { false }
        val pid = try { ptyProcess.pid() } catch (_: Exception) { -1L }
        val exitCode = if (!processAlive) try { ptyProcess.exitValue() } catch (_: Exception) { -999 } else null

        log.warn(buildString {
            appendLine("Clauditor: UNRESPONSIVE SESSION DIAGNOSTICS")
            appendLine("  PID: $pid")
            appendLine("  Process alive: $processAlive")
            if (exitCode != null) appendLine("  Exit code: $exitCode")
            appendLine("  Time since last write: ${now - lastWriteMs}ms")
            appendLine("  Time since last read:  ${if (lastReadMs > 0) "${now - lastReadMs}ms" else "never"}")
            appendLine("  Last read returned: $lastReadCount chars")
            appendLine("  Total reads: $totalReads ($totalReadChars chars)")
            appendLine("  Read thread: $readThreadName")
            appendLine("  Read currently blocked: $readBlocked")
            appendLine("  Active (thinking): $active")
            appendLine("  EDT responsive: ${!ApplicationManager.getApplication().isDispatchThread}")
        })

        // Check if the read thread is alive and what it's doing
        readThreadName?.let { name ->
            Thread.getAllStackTraces().entries.find { it.key.name == name }?.let { (thread, stack) ->
                log.warn(buildString {
                    appendLine("  Read thread state: ${thread.state}")
                    appendLine("  Read thread stack (top 5):")
                    stack.take(5).forEach { frame ->
                        appendLine("    at $frame")
                    }
                })
            }
        }
    }

    override fun close() {
        idleFuture?.cancel(false)
        echoCheckFuture?.cancel(false)
        super.close()
    }
}
