package com.clauditor.model

enum class ContextItemType { RULE, AGENT, SKILL }
enum class ContextItemLevel { PERSONAL, PROJECT }

data class ContextItem(
    val name: String,
    val description: String?,
    val type: ContextItemType,
    val level: ContextItemLevel,
    val path: java.nio.file.Path
) {
    /** The text to insert into a terminal to invoke this item. */
    val insertText: String
        get() = when (type) {
            ContextItemType.SKILL -> "/${name} "
            ContextItemType.AGENT -> "@${name} "
            ContextItemType.RULE -> ""
        }
}
