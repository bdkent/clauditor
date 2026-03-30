package com.clauditor.util

/**
 * Shared process creation helper that ensures common binary locations are in PATH.
 *
 * When the IDE is launched from the macOS dock (not a terminal), the inherited PATH
 * is minimal (/usr/bin:/bin). This helper prepends common locations so that `claude`,
 * `git`, and other tools are discoverable.
 */
object ProcessHelper {

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
        return ProcessBuilder(*command).apply {
            environment().putAll(augmentedEnv())
        }
    }
}
