package com.clauditor.model

import java.nio.file.Path
import java.time.Instant

/** One file inside a session's scratchpad directory. */
data class ScratchpadFile(
    /** Path relative to the scratchpad dir — nested entries keep their subdir prefix. */
    val name: String,
    val path: Path,
    val size: Long,
    val modified: Instant
)

/** A session's scratchpad, as shown in one collapsible group. */
data class ScratchpadGroup(
    val sessionId: String,
    val label: String,
    val isCurrent: Boolean,
    val dir: Path,
    val files: List<ScratchpadFile>,
    /** True when [files] was capped by the walk limit, so the count understates the directory. */
    val truncated: Boolean = false
)
