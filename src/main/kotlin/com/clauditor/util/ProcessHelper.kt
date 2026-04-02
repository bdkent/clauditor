package com.clauditor.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Shared process creation helper that ensures common binary locations are in PATH.
 *
 * When the IDE is launched from the macOS dock (not a terminal), the inherited PATH
 * is minimal (/usr/bin:/bin). This helper prepends common locations so that `claude`,
 * `git`, and other tools are discoverable.
 */
object ProcessHelper {

    private val log = Logger.getInstance(ProcessHelper::class.java)

    private val EXTRA_PATHS = listOf(
        "${System.getProperty("user.home")}/.local/bin",
        "/opt/homebrew/bin",
        "/usr/local/bin"
    )

    fun augmentedEnv(): MutableMap<String, String> {
        val env = System.getenv().toMutableMap()
        val currentPath = env["PATH"] ?: "/usr/bin:/bin"
        env["PATH"] = (EXTRA_PATHS + currentPath.split(":")).distinct().joinToString(":")
        return env
    }

    fun builder(vararg command: String): ProcessBuilder {
        // Resolve the binary to an absolute path using our augmented PATH,
        // because ProcessBuilder uses the parent JVM's PATH to find commands,
        // not the child environment we set.
        val resolved = if (command.isNotEmpty() && !command[0].contains(File.separator)) {
            val abs = which(command[0])
            if (abs != null) arrayOf(abs, *command.drop(1).toTypedArray()) else command
        } else {
            command
        }
        return ProcessBuilder(*resolved).apply {
            environment().putAll(augmentedEnv())
        }
    }

    /**
     * Searches for a binary in the augmented PATH directories.
     * Returns the resolved path if found, or null. Logs all checked locations.
     */
    fun which(binary: String): String? {
        val env = augmentedEnv()
        val pathDirs = (env["PATH"] ?: "").split(":")
        log.info("Clauditor: searching for '$binary' in ${pathDirs.size} PATH directories")
        for (dir in pathDirs) {
            val candidate = File(dir, binary)
            val exists = candidate.exists()
            val executable = candidate.canExecute()
            if (exists) {
                log.info("Clauditor: found '$binary' at ${candidate.absolutePath} (executable=$executable)")
                return candidate.absolutePath
            }
        }
        log.warn("Clauditor: '$binary' not found in any PATH directory. PATH=${env["PATH"]}")
        return null
    }
}
