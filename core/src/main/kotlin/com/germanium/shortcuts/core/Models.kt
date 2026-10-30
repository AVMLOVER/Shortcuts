package com.germanium.shortcuts.core

import java.util.UUID

enum class ExecutionMode { STANDARD, SHIZUKU, ROOT }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AngleUnit { DEGREES, RADIANS, GRADIANS }
data class AppSettings(val mode: ExecutionMode = ExecutionMode.STANDARD, val theme: ThemeMode = ThemeMode.SYSTEM, val angleUnit: AngleUnit = AngleUnit.DEGREES)

/** Stable string type IDs and parameter maps allow action plug-ins without changing the XML schema. */
data class ActionStep(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val parameters: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val branches: List<ActionBranch> = emptyList(),
)
data class ActionBranch(val id: String = UUID.randomUUID().toString(), val label: String = "", val steps: List<ActionStep> = emptyList())

fun allSteps(steps: List<ActionStep>, depth: Int = 0): List<ActionStep> {
    val result = ArrayList<ActionStep>()
    fun visit(block: List<ActionStep>, level: Int) {
        require(level <= 16) { "Workflow nesting exceeds 16 levels" }
        for (step in block) { result.add(step); for (branch in step.branches) visit(branch.steps, level + 1) }
    }
    visit(steps, depth)
    return result
}

data class Shortcut(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<ActionStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val icon: String = "layers",
    val color: String = "purple",
    val folderId: String = "",
    val uuid: String = stableShortcutUuid(id),
) {
    init {
        require(runCatching { UUID.fromString(uuid).toString() == uuid }.getOrDefault(false)) { throw Problem("err_invalid_data") }
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { throw Problem("err_invalid_data") }
        require(name.isNotBlank() && name.length <= 200) { throw Problem("err_invalid_data") }
        require(icon.matches(Regex("[a-z][a-z0-9_]{0,39}")) && (color.matches(Regex("[a-z][a-z0-9_]{0,39}")) || color.matches(Regex("#[0-9a-fA-F]{6}")))) { throw Problem("err_invalid_data") }
        require(createdAt >= 0 && updatedAt >= createdAt) { throw Problem("err_invalid_data") }
        require(folderId.isEmpty() || folderId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { throw Problem("err_invalid_data") }
        val flattened = allSteps(steps)
        require(flattened.size <= MAX_WORKFLOW_ACTIONS) { "Workflow exceeds $MAX_WORKFLOW_ACTIONS actions" }
        require(flattened.map { it.id }.distinct().size == flattened.size) { "Duplicate action ID" }
        require(flattened.all { it.id.isNotBlank() && it.type.isNotBlank() }) { "Action ID and type must not be empty" }
        require(flattened.all { step -> step.branches.size <= 30 && step.branches.map { it.id }.distinct().size == step.branches.size && step.branches.all { it.id.isNotBlank() && it.label.length <= 200 } }) { "Invalid branches" }
    }
}

object DemoShortcut {
    fun create() = Shortcut(
        name = "My first shortcut",
        steps = listOf(
            ActionStep(type = "text", parameters = mapOf("value" to "Hello, Shortcuts!")),
            ActionStep(type = "delay", parameters = mapOf("milliseconds" to "300")),
            ActionStep(type = "append_text", parameters = mapOf("value" to "\nThe workflow engine is ready.")),
            ActionStep(type = "output"),
        ),
    )
}

const val SHORTCUTS_VERSION = 10005
const val MAX_WORKFLOW_ACTIONS = 10_000
const val MAX_RUN_STEPS = 50_000
fun stableShortcutUuid(id: String): String = if (id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) id else UUID.nameUUIDFromBytes(("com.germanium.shortcuts:" + id).toByteArray(Charsets.UTF_8)).toString()
