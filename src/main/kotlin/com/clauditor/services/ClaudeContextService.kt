package com.clauditor.services

import com.clauditor.model.ContextItem
import com.clauditor.model.ContextItemLevel
import com.clauditor.model.ContextItemType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ClaudeContextService(private val project: Project) {

    fun scan(): List<ContextItem> {
        val items = mutableListOf<ContextItem>()
        val home = Path.of(System.getProperty("user.home"), ".claude")
        val proj = project.basePath?.let { Path.of(it, ".claude") }

        scanMarkdownDir(home.resolve("rules"), ContextItemType.RULE, ContextItemLevel.PERSONAL, items)
        scanMarkdownDir(home.resolve("agents"), ContextItemType.AGENT, ContextItemLevel.PERSONAL, items)
        scanSkillsDir(home.resolve("skills"), ContextItemLevel.PERSONAL, items)

        if (proj != null) {
            scanMarkdownDir(proj.resolve("rules"), ContextItemType.RULE, ContextItemLevel.PROJECT, items)
            scanMarkdownDir(proj.resolve("agents"), ContextItemType.AGENT, ContextItemLevel.PROJECT, items)
            scanSkillsDir(proj.resolve("skills"), ContextItemLevel.PROJECT, items)
        }

        return items.sortedWith(compareBy({ it.type }, { it.name }))
    }

    private fun scanMarkdownDir(
        dir: Path,
        type: ContextItemType,
        level: ContextItemLevel,
        out: MutableList<ContextItem>
    ) {
        if (!Files.isDirectory(dir)) return
        try {
            Files.list(dir).use { stream ->
                stream.filter { it.toString().endsWith(".md") && Files.isRegularFile(it) }
                    .forEach { file ->
                        val (name, description) = parseFrontmatter(file)
                        out.add(ContextItem(
                            name = name ?: file.fileName.toString().removeSuffix(".md"),
                            description = description,
                            type = type,
                            level = level,
                            path = file
                        ))
                    }
            }
        } catch (_: Exception) {}
    }

    private fun scanSkillsDir(dir: Path, level: ContextItemLevel, out: MutableList<ContextItem>) {
        if (!Files.isDirectory(dir)) return
        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { skillDir ->
                        val skillFile = skillDir.resolve("SKILL.md")
                        val description = if (Files.isRegularFile(skillFile)) {
                            parseFrontmatter(skillFile).second
                        } else null
                        out.add(ContextItem(
                            name = skillDir.fileName.toString(),
                            description = description,
                            type = ContextItemType.SKILL,
                            level = level,
                            path = skillDir
                        ))
                    }
            }
        } catch (_: Exception) {}
    }

    /** Extracts name and description from YAML frontmatter (--- delimited). */
    private fun parseFrontmatter(file: Path): Pair<String?, String?> {
        var name: String? = null
        var description: String? = null
        try {
            val lines = Files.readAllLines(file)
            if (lines.isEmpty() || lines[0].trim() != "---") return null to null
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.trim() == "---") break
                if (line.startsWith("name:")) {
                    name = line.substringAfter("name:").trim().removeSurrounding("\"")
                }
                if (line.startsWith("description:")) {
                    description = line.substringAfter("description:").trim().removeSurrounding("\"")
                }
            }
        } catch (_: Exception) {}
        return name to description
    }

    companion object {
        fun getInstance(project: Project): ClaudeContextService =
            project.getService(ClaudeContextService::class.java)
    }
}
