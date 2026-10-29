package com.germanium.shortcuts.core

enum class InputOrigin { NONE, SYSTEM_SHARE, SHORTCUT }

object InputProvenance {
    private val inputActions = setOf("get_text_from_input", "get_images_from_input", "get_urls_from_input", "get_files_from_input", "get_numbers_from_input")
    private val implicitInput = inputActions + setOf("calculate", "calculate_expression", "output", "append_text", "encode_base64", "decode_base64", "encode_url", "decode_url", "hash_text", "count", "statistics", "join_text", "round_number", "factor_number")
    fun outputOrigins(step: ActionStep, c: RunContext): Set<InputOrigin> = when (step.type) {
        "share_input" -> if (c.inputOrigin == InputOrigin.NONE) emptySet() else setOf(c.inputOrigin)
        "create_variable", "set_variable", "append_variable" -> c.variables.origins(step.parameters["name"].orEmpty())
        "get_variable" -> c.referenceOrigins(step.parameters["name"].orEmpty())
        else -> step.parameters.values.flatMap { c.originsOf(it) }.toSet() + if (step.type in implicitInput && step.parameters.keys.none { it in setOf("source", "value", "left", "expression") }) c.valueOrigins else emptySet()
    }
    private data class Analysis(val outputs: MutableMap<String, Boolean> = mutableMapOf(), val names: MutableMap<String, Boolean> = mutableMapOf(), var previous: Boolean = false) {
        fun fork() = Analysis(outputs.toMutableMap(), names.toMutableMap(), previous)
        fun reference(name: String): Boolean = when { name == "input" -> previous; name.startsWith('@') -> outputs[name.drop(1).substringBefore('.')] == true; else -> names[name] == true }
        fun derived(text: String) = TemplateEditing.tokens(text).any { reference(it.name) }
    }
    /** Branches merge possible origins; loop-local aliases are restored on leaving their scope. */
    fun isSharedReference(steps: List<ActionStep>, target: String, expression: String): Boolean {
        fun visit(block: List<ActionStep>, env: Analysis): Boolean? {
            for (step in block) {
                if (step.id == target) return env.derived(expression)
                if (!step.enabled) continue
                var origin = when (step.type) {
                    "share_input" -> true
                    "get_variable" -> env.reference(step.parameters["name"].orEmpty())
                    "create_variable", "set_variable", "append_variable" -> env.derived(step.parameters["value"] ?: "{{input}}") || step.type == "append_variable" && env.names[step.parameters["name"]] == true
                    in inputActions -> if (step.parameters["source"] in listOf(null, "{{input}}")) true else env.derived(step.parameters.getValue("source"))
                    else -> step.parameters.values.any(env::derived) || step.type in implicitInput && step.parameters.keys.none { it in setOf("source", "value", "left", "expression") } && env.previous
                }
                if (step.branches.isNotEmpty()) {
                    val previousLocals = listOf("repeat_item", "repeat_index").associateWith { env.names[it] }
                    val branches = step.branches.map { branch ->
                        val nested = env.fork()
                        if (step.type == "repeat_each") nested.names["repeat_item"] = env.derived(step.parameters["source"] ?: "{{input}}")
                        if (step.type in listOf("repeat", "repeat_each", "repeat_until")) nested.names["repeat_index"] = false
                        visit(branch.steps, nested)?.let { return it }
                        nested
                    }.toMutableList()
                    if (step.type == "if" && step.branches.none { it.id == "else" } || step.type == "repeat_each") branches += env.fork()
                    origin = branches.any { it.previous }
                    val nameKeys = branches.flatMap { it.names.keys }.toSet()
                    nameKeys.forEach { name -> env.names[name] = branches.any { it.names[name] == true } }
                    branches.flatMap { it.outputs.keys }.toSet().forEach { id -> env.outputs[id] = branches.any { it.outputs[id] == true } }
                    if (step.type in listOf("repeat", "repeat_each", "repeat_until")) previousLocals.forEach { (name, flag) -> if (flag == null) env.names.remove(name) else env.names[name] = flag }
                }
                if (step.type in setOf("create_variable", "set_variable", "append_variable")) env.names[step.parameters["name"].orEmpty()] = origin
                step.parameters["resultVariable"]?.let { env.names[it] = origin }
                if (ActionOutputs.produces(step.type)) { env.outputs[step.id] = origin; env.previous = origin }
            }
            return null
        }
        return visit(steps, Analysis()) ?: false
    }
}
