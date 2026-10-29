package com.germanium.shortcuts.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

interface ActionHandler {
    val type: String
    fun validate(parameters: Map<String, String>)
    suspend fun execute(parameters: Map<String, String>, context: RunContext): Value
}
class ActionRegistry(handlers: List<ActionHandler>) {
    private val entries = handlers.associateBy { it.type }
    init { require(entries.size == handlers.size) { throw Problem("err_invalid_data") } }
    fun require(type: String): ActionHandler = requireNotNull(entries[type]) { throw Problem("err_unknown_action") }
}
enum class RunStatus { SUCCEEDED, FAILED }
data class StepResult(val stepId: String, val type: String, val value: Value, val properties: Map<String, Value> = emptyMap()) { val output get() = value.text() }
data class RunResult(
    val status: RunStatus, val steps: List<StepResult>, val error: String? = null,
    val variables: Map<String, Value> = emptyMap(), val failedStepId: String? = null, val failure: Throwable? = null,
    val executedCount: Int = steps.size, val historyTruncated: Boolean = false, val finalValue: Value? = null, val valueOrigins: Set<InputOrigin> = emptySet(),
) { val output: String get() = finalValue?.text() ?: steps.lastOrNull()?.output.orEmpty() }

data class RunTrace(val event: String, val stepId: String? = null, val type: String? = null, val failure: Throwable? = null)
class ShortcutEngine(private val registry: ActionRegistry = builtInActions(), private val platform: PlatformActions = PlatformActions.Unavailable) {
    private val mutex = Mutex()
    private data class Node(val step: ActionStep, val handler: ActionHandler?, val branches: Map<String, List<Node>>)
    private fun compile(steps: List<ActionStep>): List<Node> = steps.filter { it.enabled }.map { step ->
        step.parameters["resultVariable"]?.takeIf(String::isNotBlank)?.let(::validateVariableName)
        val handler = when (step.type) {
            "if" -> { require(step.branches.count { it.id == "then" } == 1 && step.branches.all { it.id in listOf("then", "else") }) { throw Problem("err_branches") }; require(step.parameters.getOrDefault("operator", "equals") in CONDITIONS + SOURCE_CONDITIONS) { throw Problem("err_condition") }; null }
            "menu" -> { require(step.branches.isNotEmpty() && step.branches.all { it.label.isNotBlank() }) { throw Problem("err_menu") }; null }
            "repeat", "repeat_each", "repeat_until" -> { require(step.branches.size == 1 && step.branches.single().id == "body") { throw Problem("err_branches") }; if (step.type == "repeat_until") require(step.parameters.getOrDefault("operator", "equals") in CONDITIONS) { throw Problem("err_condition") }; null }
            "stop", "return", "end_shortcut", "run_shortcut" -> { require(step.branches.isEmpty()) { throw Problem("err_branches") }; null }
            else -> { require(step.branches.isEmpty()) { throw Problem("err_branches") }; registry.require(step.type).also { it.validate(step.parameters) } }
        }
        Node(step, handler, step.branches.associate { it.id to compile(it.steps) })
    }
    fun validate(shortcut: Shortcut) { compile(shortcut.steps) }
    private sealed interface Frame
    private class Block(val nodes: List<Node>, var index: Int = 0, val complete: (() -> Unit)? = null) : Frame
    private class Loop(val node: Node, val count: Int, val items: List<Value>?, val previousIndex: Value?, val previousItem: Value?, val previousIndexOrigins: Set<InputOrigin>, val previousItemOrigins: Set<InputOrigin>, var index: Int = 0, val results: MutableList<Value> = mutableListOf(), var resultLength: Long = 0) : Frame
    private class Budget(var executed: Int = 0)
    suspend fun run(shortcut: Shortcut, mode: ExecutionMode, input: String = "", onStep: (String) -> Unit = {}, onTrace: ((RunTrace) -> Unit)? = null, angleUnit: AngleUnit = AngleUnit.DEGREES, typedInput: Value? = null, inputOrigin: InputOrigin = InputOrigin.NONE): RunResult {
        check(mutex.tryLock()) { "err_busy" }
        try { return execute(shortcut, mode, typedInput ?: Value.Text(input), inputOrigin, onStep, onTrace, angleUnit, Budget(), emptySet()) }
        finally { mutex.unlock() }
    }
    private suspend fun execute(shortcut: Shortcut, mode: ExecutionMode, originalInput: Value, source: InputOrigin, onStep: (String) -> Unit, onTrace: ((RunTrace) -> Unit)?, angleUnit: AngleUnit, budget: Budget, ancestors: Set<String>): RunResult {
        require(ancestors.size < 16 && shortcut.uuid !in ancestors) { throw Problem("err_call_cycle") }
        val results = mutableListOf<StepResult>(); val variables = VariableScope()
        val stack = java.util.ArrayDeque<Frame>()
        var currentStep: String? = null; var executed = 0; var value: Value = originalInput
        var origins = if (source == InputOrigin.NONE) emptySet() else setOf(source)
        var historyLength = 0L; var historyTruncated = false
        fun trace(event: String, step: ActionStep? = null, failure: Throwable? = null) { onTrace?.let { callback -> runCatching { callback(RunTrace(event, step?.id, step?.type, failure)) } } }
        fun finish(step: ActionStep, properties: Map<String, Value> = emptyMap()) {
            currentStep = step.id
            require(value.text().length <= if (step.type in listOf("encode_base64", "decode_base64")) 4 * 1024 * 1024 else 262144) { throw Problem("err_size") }
            if (ActionOutputs.produces(step.type)) {
                variables.record(step.id, value, origins)
                properties.forEach { (key, property) -> variables.record("${step.id}.$key", property, origins) }
                step.parameters["resultVariable"]?.takeIf(String::isNotBlank)?.let { variables.set(it, value, origins) }
            }
            results += StepResult(step.id, step.type, value, properties); historyLength += value.text().length
            while (results.size > 1 && (historyLength > 2097152 || results.size > 1000)) { historyLength -= results.removeAt(0).output.length; historyTruncated = true }
            trace("step_end", step)
        }
        fun restore(loop: Loop) {
            if (loop.previousIndex == null) variables.remove("repeat_index") else variables.set("repeat_index", loop.previousIndex, loop.previousIndexOrigins)
            if (loop.items != null) { if (loop.previousItem == null) variables.remove("repeat_item") else variables.set("repeat_item", loop.previousItem, loop.previousItemOrigins) }
        }
        fun unwind() { stack.toList().asReversed().filterIsInstance<Loop>().forEach { runCatching { restore(it) } }; stack.clear() }
        fun success() = RunResult(RunStatus.SUCCEEDED, results, variables = variables.snapshot(), executedCount = executed, historyTruncated = historyTruncated, finalValue = value, valueOrigins = origins)
        try {
            trace("run_start"); stack.addLast(Block(compile(shortcut.steps)))
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                when (val frame = stack.peekLast()) {
                    is Block -> {
                        if (frame.index >= frame.nodes.size) { stack.removeLast(); frame.complete?.invoke(); continue }
                        val node = frame.nodes[frame.index++]; val step = node.step
                        executed++; require(++budget.executed <= 5000) { throw Problem("err_step_limit") }
                        currentStep = step.id; onStep(step.id); trace("step_start", step)
                        val context = RunContext(mode, value, variables, angleUnit, originalInput, source, origins)
                        when (step.type) {
                            "if" -> {
                                val selected = if (evaluateCondition(step.parameters, context)) "then" else "else"
                                trace("branch_$selected", step)
                                stack.addLast(Block(node.branches[selected].orEmpty(), complete = { finish(step) }))
                            }
                            "menu" -> {
                                val selected = platform.chooseMenu(context.text(step.parameters, "title", shortcut.name), step.branches.map { it.id to context.render(it.label) }) ?: throw CancellationException("Menu cancelled")
                                val branch = step.branches.find { it.id == selected } ?: throw Problem("err_menu")
                                variables.set("menu_choice", Value.Text(context.render(branch.label))); trace("menu_selected_${branch.id}", step)
                                stack.addLast(Block(node.branches.getValue(selected), complete = { finish(step) }))
                            }
                            "repeat", "repeat_each", "repeat_until" -> {
                                val items = if (step.type == "repeat_each") asList(context.resolve(step.parameters["source"] ?: "{{input}}")) else null
                                val count = if (step.type == "repeat_until") 1000 else items?.size ?: (context.text(step.parameters, "count", "2").toIntOrNull() ?: throw Problem("err_repeat"))
                                require(count in (if (items == null) 1 else 0)..1000) { throw Problem("err_repeat") }
                                stack.addLast(Loop(node, count, items, variables.snapshot()["repeat_index"], variables.snapshot()["repeat_item"], variables.origins("repeat_index"), variables.origins("repeat_item")))
                            }
                            "run_shortcut" -> {
                                val choices = platform.shortcutsForCall()
                                if (step.parameters["selection"] == "ask" && choices.isEmpty()) throw Problem("err_shortcut_missing")
                                val target = if (step.parameters["selection"] == "ask") platform.chooseShortcut(choices) ?: throw CancellationException("Shortcut selection cancelled") else context.text(step.parameters, "shortcutUuid")
                                val child = platform.shortcutsForCall().firstOrNull { it.uuid == target } ?: throw Problem("err_shortcut_missing")
                                val returned = execute(child, mode, context.resolve(step.parameters["input"] ?: "{{input}}"), InputOrigin.SHORTCUT, { onStep(step.id) }, onTrace, angleUnit, budget, ancestors + shortcut.uuid)
                                if (returned.status == RunStatus.FAILED) throw returned.failure ?: Problem("err_child_shortcut")
                                value = returned.finalValue ?: Value.Text(returned.output); origins = returned.valueOrigins
                                finish(step)
                            }
                            "return" -> { value = context.resolve(step.parameters["value"] ?: "{{input}}"); origins = context.originsOf(step.parameters["value"] ?: "{{input}}"); finish(step); unwind(); trace("run_returned"); return success() }
                            "end_shortcut" -> { value = Value.Text(""); origins = emptySet(); finish(step); unwind(); trace("run_stopped"); return success() }
                            "stop" -> { value = context.resolve(step.parameters["value"] ?: "{{input}}"); origins = context.originsOf(step.parameters["value"] ?: "{{input}}"); finish(step); unwind(); trace("run_stopped"); return success() }
                            else -> { value = node.handler!!.execute(step.parameters, context); origins = InputProvenance.outputOrigins(step, context); finish(step) }
                        }
                    }
                    is Loop -> {
                        if (frame.node.step.type == "repeat_until" && frame.index > 0) {
                            currentStep = frame.node.step.id
                            if (evaluateCondition(frame.node.step.parameters, RunContext(mode, value, variables, angleUnit, originalInput, source, origins))) {
                                restore(frame); stack.removeLast(); finish(frame.node.step, mapOf("repeatCount" to Value.Number(frame.index.toBigDecimal()))); continue
                            }
                            require(frame.index < frame.count) { throw Problem("err_repeat_limit") }
                        }
                        if (frame.index >= frame.count) {
                            restore(frame); stack.removeLast()
                            if (frame.items != null) value = Value.ListValue(frame.results)
                            finish(frame.node.step)
                        } else {
                            variables.set("repeat_index", Value.Number((frame.index + 1).toBigDecimal()))
                            frame.items?.let { value = it[frame.index]; variables.set("repeat_item", value, origins) }
                            frame.index++
                            stack.addLast(Block(frame.node.branches.getValue("body"), complete = {
                                if (frame.items != null) {
                                    currentStep = frame.node.step.id
                                    val length = value.text().length.toLong() + 1
                                    require(frame.resultLength + length <= 262144) { throw Problem("err_size") }
                                    frame.resultLength += length; frame.results += value
                                }
                            }))
                        }
                    }
                }
            }
            trace("run_success"); return success()
        } catch (e: CancellationException) { unwind(); trace("run_cancelled"); throw e
        } catch (e: Exception) { unwind(); trace("run_failed", failure = e); return RunResult(RunStatus.FAILED, results, e.message ?: e.javaClass.simpleName, variables.snapshot(), currentStep, e, executed, historyTruncated)
        }
    }
    companion object { val SOURCE_CONDITIONS = setOf("source_system_share", "source_shortcut"); val CONDITIONS = setOf("equals", "not_equals", "contains", "not_contains", "greater", "less", "greater_equal", "less_equal", "is_empty", "is_not_empty", "is_true", "matches") }
}

internal class Handler(
    override val type: String, val check: (Map<String, String>) -> Unit = {},
    val run: suspend (Map<String, String>, RunContext) -> Value,
) : ActionHandler {
    override fun validate(parameters: Map<String, String>) = check(parameters)
    override suspend fun execute(parameters: Map<String, String>, context: RunContext) = run(parameters, context)
}
fun builtInActions(platform: PlatformActions = PlatformActions.Unavailable) = ActionRegistry(contentActions(platform) + extraActions(platform) + libraryActions(platform) + listOf(
    Handler("text", { require(it.containsKey("value")) { throw Problem("err_invalid_data") } }) { p, c -> c.resolve(p.getValue("value")) },
    Handler("append_text", { require(it.containsKey("value")) { throw Problem("err_invalid_data") } }) { p, c -> Value.Text(c.text(p, "target", "{{input}}") + c.text(p, "value")) },
    Handler("delay", { p ->
        require(p.containsKey("milliseconds")) { throw Problem("err_invalid_data") }
        if (!p.getValue("milliseconds").contains("{{")) durationMillis(p.getValue("milliseconds"), p["unit"] ?: "milliseconds")
    }) { p, c ->
        delay(durationMillis(c.text(p, "milliseconds"), p["unit"] ?: "milliseconds")); c.value
    },
    Handler("output") { p, c -> p["value"]?.let(c::resolve) ?: c.value },
    Handler("set_variable", { p ->
        validateVariableName(p["name"].orEmpty())
        require(p.getOrDefault("valueType", "auto") in listOf("auto", "text", "number", "boolean")) { throw Problem("err_invalid_data") }
    }) { p, c ->
        val source = p["value"] ?: "{{input}}"
        val value = when (p.getOrDefault("valueType", "auto")) {
            "text" -> Value.Text(c.render(source))
            "number" -> Value.Number(parseNumber(c.render(source)))
            "boolean" -> Value.BooleanValue(when (c.render(source)) { "true" -> true; "false" -> false; else -> throw Problem("err_invalid_data") })
            else -> c.resolve(source)
        }
        c.variables.set(p.getValue("name"), value, c.originsOf(source)); value
    },
    Handler("get_variable", { require(it["name"].orEmpty().isNotBlank()) { throw Problem("err_variable_name") } }) { p, c -> c.reference(p.getValue("name")) },
    Handler("calculate") { p, c ->
        val expression = p["expression"]?.let(c::expression) ?: run {
            val left = c.resolve(p["left"] ?: "{{input}}").number(); val right by lazy { c.resolve(p["right"] ?: "1").number() }
            val operator = p.getOrDefault("operation", "+")
            when (operator) {
                "+", "-", "*", "/", "^" -> "($left) $operator ($right)"
                "%" -> "($left)*($right)/100"
                "mod", "gcd", "lcm", "min", "max", "root" -> "$operator($left,$right)"
                "round" -> {
                    val function = when (p.getOrDefault("rounding", "nearest")) { "nearest" -> "round"; "up" -> "ceil"; "down" -> "floor"; else -> throw Problem("err_math") }
                    val digits = c.resolve(p["digits"] ?: "0").number().intValueExact()
                    "$function($left,$digits)"
                }
                "sin", "cos", "tan", "asin", "acos", "atan", "abs", "sqrt", "cbrt", "ceil", "floor" -> "$operator($left)"
                else -> throw Problem("err_math")
            }
        }
        Value.Number(Calculator(c.angleUnit) { c.reference(it).number() }.evaluate(expression))
    },
    Handler("dictionary", { p ->
        val entries = dictionaryEntries(p)
        require(entries.all { it.key.isNotBlank() && it.type in listOf("text", "number", "boolean", "auto") } && entries.map { it.key }.distinct().size == entries.size) { throw Problem("err_dictionary") }
    }) { p, c -> dictionaryFromParameters(p, c) },
    Handler("get_dictionary_value", { require(it["key"].orEmpty().isNotBlank()) { throw Problem("err_dictionary") } }) { p, c ->
        val dictionary = c.resolve(p["dictionary"] ?: "{{input}}") as? Value.Dictionary ?: throw Problem("err_dictionary")
        val key = c.text(p, "key")
        dictionary.entries[key] ?: throw Problem("err_dictionary")
    },
    Handler("set_dictionary_value", { p ->
        require(p["key"].orEmpty().isNotBlank()) { throw Problem("err_dictionary") }
        require(p.getOrDefault("valueType", "auto") in listOf("auto", "text", "number", "boolean")) { throw Problem("err_invalid_data") }
    }) { p, c ->
        val dictionary = c.resolve(p["dictionary"] ?: "{{input}}") as? Value.Dictionary ?: throw Problem("err_dictionary")
        val key = c.text(p, "key"); require(key.isNotBlank()) { throw Problem("err_dictionary") }
        Value.Dictionary(dictionary.entries + (key to dictionaryValue(p.getOrDefault("valueType", "auto"), p["value"].orEmpty(), c)))
    },
    Handler("shell", { p ->
        require(p["command"].orEmpty().isNotBlank() && p.getValue("command").length <= 32768) { throw Problem("err_size") }
        require(p.getOrDefault("timeoutSeconds", "30").toLongOrNull()?.let { it in 1..120 } == true) { throw Problem("err_invalid_data") }
    }) { p, c ->
        val result = platform.shell(c.text(p, "command"), c.mode, p.getOrDefault("timeoutSeconds", "30").toLong() * 1000)
        c.variables.set("shell_stdout", Value.Text(result.stdout)); c.variables.set("shell_stderr", Value.Text(result.stderr))
        c.variables.set("shell_exit_code", Value.Number(result.exitCode.toBigDecimal()))
        require(!result.truncated) { throw Problem("err_size") }
        check(result.exitCode == 0) { throw Problem("err_invalid_data") }
        Value.Text(result.stdout)
    },
    Handler("intent", { p ->
        require(p.getOrDefault("action", "android.intent.action.VIEW").isNotBlank()) { throw Problem("err_invalid_data") }
        require(p["className"].isNullOrBlank() || !p["packageName"].isNullOrBlank()) { throw Problem("err_invalid_data") }
        val extras = readEntrySpecs(p, "extra", "extras")
        require(extras.size <= 50 && extras.all { it.key.isNotBlank() && it.type in listOf("string", "int", "long", "double", "boolean") } && extras.map { it.key }.distinct().size == extras.size) { throw Problem("err_invalid_data") }
    }) { p, c ->
        platform.launchIntent(IntentSpec(
            action = c.text(p, "action", "android.intent.action.VIEW"), data = c.text(p, "data"), mimeType = c.text(p, "mimeType"),
            packageName = c.text(p, "packageName"), className = c.text(p, "className"),
            categories = c.text(p, "categories").split('\n').map(String::trim).filter(String::isNotEmpty),
            extras = intentExtrasFromParameters(p, c::render),
        )); c.value
    },
))
