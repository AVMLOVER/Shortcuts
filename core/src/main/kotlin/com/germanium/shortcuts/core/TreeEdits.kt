package com.germanium.shortcuts.core

object TreeEdits {
    /** Moves the whole subtree to an explicit insertion point, preserving IDs and rejecting cycles. */
    fun place(steps: List<ActionStep>, id: String, targetId: String, before: Boolean): List<ActionStep> {
        if (id == targetId) return steps
        val source = pathTo(steps, id)?.lastOrNull() ?: return steps
        val targetPath = pathTo(steps, targetId) ?: return steps
        if (targetPath.any { it.id == source.id }) return steps
        val remaining = remove(steps, id)
        return if (before) insertBefore(remaining, targetId, source) else insertAfter(remaining, targetId, source)
    }
    fun placeInBranch(steps: List<ActionStep>, id: String, owner: String, branch: String): List<ActionStep> {
        val source = pathTo(steps, id)?.lastOrNull() ?: return steps
        val ownerPath = pathTo(steps, owner) ?: return steps
        if (ownerPath.any { it.id == id } || ownerPath.last().branches.none { it.id == branch }) return steps
        return update(remove(steps, id), owner) { parent -> parent.copy(branches = parent.branches.map { if (it.id == branch) it.copy(steps = it.steps + source) else it }) }
    }
    /** Outputs known to have completed before this action, excluding its unfinished ancestors and sibling branches. */
    fun pathTo(steps: List<ActionStep>, id: String): List<ActionStep>? {
        for (step in steps) {
            if (step.id == id) return listOf(step)
            for (branch in step.branches) pathTo(branch.steps, id)?.let { return listOf(step) + it }
        }
        return null
    }
    fun availableBefore(steps: List<ActionStep>, targetId: String): List<ActionStep> {
        val path = pathTo(steps, targetId) ?: return emptyList()
        val result = ArrayList<ActionStep>()
        var block = steps
        for ((index, ancestor) in path.withIndex()) {
            result.addAll(block.takeWhile { it.id != ancestor.id }.filter { it.enabled && ActionOutputs.produces(it.type) })
            if (index < path.lastIndex) block = ancestor.branches.first { branch -> branch.steps.any { it.id == path[index + 1].id } }.steps
        }
        return result
    }
    fun availableAtRepeatEnd(steps: List<ActionStep>, targetId: String): List<ActionStep> {
        val loop = pathTo(steps, targetId)?.lastOrNull() ?: return emptyList()
        return availableBefore(steps, targetId) + loop.branches.firstOrNull { it.id == "body" }?.steps.orEmpty().filter { it.enabled && ActionOutputs.produces(it.type) }
    }
    fun insertAfter(steps: List<ActionStep>, id: String, action: ActionStep): List<ActionStep> = steps.flatMap { step ->
        if (step.id == id) listOf(step, action) else listOf(step.copy(branches = step.branches.map { it.copy(steps = insertAfter(it.steps, id, action)) }))
    }
    fun insertBefore(steps: List<ActionStep>, id: String, action: ActionStep): List<ActionStep> = steps.flatMap { step ->
        if (step.id == id) listOf(action, step) else listOf(step.copy(branches = step.branches.map { it.copy(steps = insertBefore(it.steps, id, action)) }))
    }
    fun update(steps: List<ActionStep>, id: String, transform: (ActionStep) -> ActionStep): List<ActionStep> {
        for ((index, step) in steps.withIndex()) {
            if (step.id == id) return steps.toMutableList().also { it[index] = transform(step) }
            for ((branchIndex, branch) in step.branches.withIndex()) {
                val children = update(branch.steps, id, transform)
                if (children !== branch.steps) {
                    val branches = step.branches.toMutableList().also { it[branchIndex] = branch.copy(steps = children) }
                    return steps.toMutableList().also { it[index] = step.copy(branches = branches) }
                }
            }
        }
        return steps
    }
    fun remove(steps: List<ActionStep>, id: String): List<ActionStep> = steps.filter { it.id != id }.map { step -> step.copy(branches = step.branches.map { it.copy(steps = remove(it.steps, id)) }) }
    fun move(steps: List<ActionStep>, id: String, delta: Int): List<ActionStep> {
        val index = steps.indexOfFirst { it.id == id }
        if (index >= 0) { val target = index + delta; return if (target in steps.indices) steps.toMutableList().also { java.util.Collections.swap(it, index, target) } else steps }
        return steps.map { step -> step.copy(branches = step.branches.map { it.copy(steps = move(it.steps, id, delta)) }) }
    }
    fun add(steps: List<ActionStep>, owner: String?, branch: String?, action: ActionStep): List<ActionStep> = if (owner == null) steps + action else update(steps, owner) { step ->
        step.copy(branches = step.branches.map { if (it.id == branch) it.copy(steps = it.steps + action) else it })
    }
    fun duplicate(step: ActionStep): ActionStep {
        return duplicateSteps(listOf(step)).single()
    }
    fun duplicateSteps(steps: List<ActionStep>): List<ActionStep> {
        val ids = allSteps(steps).associate { it.id to java.util.UUID.randomUUID().toString() }
        fun references(text: String): String = Regex("\\{\\{\\s*@([^{}]+?)\\s*\\}\\}").replace(text) { match ->
            val path = match.groupValues[1].trim(); val source = path.substringBefore('.')
            ids[source]?.let { "{{@$it${path.removePrefix(source)}}}" } ?: match.value
        }
        fun copy(source: ActionStep): ActionStep = source.copy(id = ids.getValue(source.id), parameters = source.parameters.mapValues { (key, value) ->
            if (source.type == "get_variable" && key == "name" && value.startsWith('@')) {
                val path = value.drop(1); val id = path.substringBefore('.')
                ids[id]?.let { "@$it${path.removePrefix(id)}" } ?: value
            } else references(value)
        }, branches = source.branches.map { branch ->
            branch.copy(id = if (source.type in listOf("if", "repeat", "repeat_each", "repeat_until")) branch.id else java.util.UUID.randomUUID().toString(), label = references(branch.label), steps = branch.steps.map(::copy))
        })
        return steps.map(::copy)
    }
    fun copyAfter(steps: List<ActionStep>, id: String): List<ActionStep> = steps.flatMap { step ->
        if (step.id == id) listOf(step, duplicate(step)) else listOf(step.copy(branches = step.branches.map { it.copy(steps = copyAfter(it.steps, id)) }))
    }
}
