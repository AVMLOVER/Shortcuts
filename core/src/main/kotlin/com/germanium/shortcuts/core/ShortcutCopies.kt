package com.germanium.shortcuts.core

object ShortcutCopies {
    private fun targets(steps: List<ActionStep>, replacements: Map<String, String>): List<ActionStep> = steps.map { step ->
        step.copy(parameters = if (step.type == "run_shortcut") step.parameters.mapValues { (key, value) -> if (key == "shortcutUuid") replacements[value] ?: value else value } else step.parameters,
            branches = step.branches.map { it.copy(steps = targets(it.steps, replacements)) })
    }
    fun importCopies(sources: List<Shortcut>, now: Long): List<Shortcut> {
        require(sources.map { it.uuid }.distinct().size == sources.size) { throw Problem("err_invalid_data") }
        val ids = sources.associate { it.uuid to java.util.UUID.randomUUID().toString() }
        return sources.map { it.copy(id = ids.getValue(it.uuid), uuid = ids.getValue(it.uuid), folderId = "", updatedAt = maxOf(now, it.updatedAt), steps = targets(it.steps, ids)) }
    }
    fun duplicate(source: Shortcut, now: Long = System.currentTimeMillis()) = source.copy(
        id = java.util.UUID.randomUUID().toString(),
        uuid = java.util.UUID.randomUUID().toString(),
        name = source.name.take(195) + " Copy",
        steps = TreeEdits.duplicateSteps(source.steps),
        createdAt = now,
        updatedAt = now,
    ).let { copy -> copy.copy(steps = targets(copy.steps, mapOf(source.uuid to copy.uuid))) }
}
