package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConditionalRepeatTest {
    private fun loop(left: String = "{{input}}", operator: String = "greater_equal", right: String = "3", body: List<ActionStep> = listOf(ActionStep(type = "calculate", parameters = mapOf("left" to "{{input}}", "operation" to "+", "right" to "1")))) = ActionStep(type = "repeat_until", parameters = mapOf("left" to left, "operator" to operator, "right" to right), branches = listOf(ActionBranch("body", steps = body)))
    private suspend fun run(vararg steps: ActionStep) = ShortcutEngine().run(Shortcut(name = "Repeat tests", steps = steps.toList()), ExecutionMode.STANDARD)
    private fun number(value: String) = ActionStep(type = "number", parameters = mapOf("value" to value))
    @Test fun repeatsUntilGreaterOrEqualAndExportsASeparateCount() = runTest {
        val repeat = loop(); val result = run(number("0"), repeat)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("3", result.output)
        assertEquals("3", result.steps.last().properties["repeatCount"]?.text())
        val captured = run(number("0"), repeat, ActionStep(type = "text", parameters = mapOf("value" to "{{@${repeat.id}}}/{{@${repeat.id}.repeatCount}}")))
        assertEquals("3/3", captured.output)
    }
    @Test fun alwaysRunsTheBodyAtLeastOnce() = runTest {
        val result = run(number("10"), loop(right = "3"))
        assertEquals("11", result.output); assertEquals("1", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun lessThanUsesTheSameConditionEvaluatorAsIf() = runTest {
        val body = listOf(ActionStep(type = "calculate", parameters = mapOf("operation" to "-", "right" to "2")))
        val result = run(number("10"), loop(operator = "less", right = "5", body = body))
        assertEquals("4", result.output); assertEquals("3", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun endConditionCanReferenceACompletedBodyOutputOrNamedVariable() = runTest {
        val body = ActionStep(type = "calculate", parameters = mapOf("right" to "1"))
        val repeat = loop(left = "{{@${body.id}}}", right = "{{limit}}", body = listOf(body))
        val result = run(ActionStep(type = "create_variable", parameters = mapOf("name" to "limit", "valueType" to "number", "value" to "2")), number("0"), repeat)
        assertEquals("2", result.output); assertEquals("2", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun nestedRepeatsRestoreTheOuterIterationIndex() = runTest {
        val inner = loop(operator = "is_not_empty", body = listOf(ActionStep(type = "text", parameters = mapOf("value" to "x"))))
        val outer = loop(right = "2", body = listOf(inner, ActionStep(type = "number", parameters = mapOf("value" to "{{repeat_index}}"))))
        val result = run(outer)
        assertEquals("2", result.output); assertEquals("2", result.steps.last().properties["repeatCount"]?.text())
        assertFalse(result.variables.containsKey("repeat_index"))
    }
    @Test fun aConditionThatNeverMatchesFailsAtTheBound() = runTest {
        val repeat = loop(operator = "equals", right = "never", body = emptyList())
        val result = run(repeat)
        assertEquals(RunStatus.FAILED, result.status); assertEquals("err_repeat_limit", (result.failure as Problem).key)
    }
    @Test fun completesOneHundredThousandIterationsWithMultipleBodySteps() = runTest {
        var count = 0
        val engine = ShortcutEngine(ActionRegistry(listOf(
            Handler("tick") { _, _ -> Value.Number((++count).toBigDecimal()) },
            Handler("echo") { _, context -> context.value },
        )))
        val repeat = loop(right = "100000", body = listOf(ActionStep(type = "tick"), ActionStep(type = "echo")))
        val result = engine.run(Shortcut(name = "Full limit", steps = listOf(repeat)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals(100000, count); assertEquals("100000", result.output)
        assertEquals("100000", result.steps.last().properties["repeatCount"]?.text())
        assertEquals(200001, result.executedCount)
        assertTrue(result.historyTruncated); assertTrue(result.steps.size <= 1000)
    }
    @Test fun doesNotExecuteIterationOneHundredThousandAndOne() = runTest {
        var count = 0
        val engine = ShortcutEngine(ActionRegistry(listOf(Handler("tick") { _, _ -> Value.Number((++count).toBigDecimal()) })))
        val repeat = loop(right = "100001", body = listOf(ActionStep(type = "tick")))
        val result = engine.run(Shortcut(name = "Bounded", steps = listOf(repeat)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("err_repeat_limit", (result.failure as Problem).key); assertEquals(100000, count)
    }
    @Test fun leavingConditionalRepeatRestoresTheOrdinaryExecutionBudget() = runTest {
        val tick = Handler("tick") { _, _ -> Value.Text("x") }
        val engine = ShortcutEngine(ActionRegistry(listOf(tick)))
        val conditional = loop(operator = "is_not_empty", body = listOf(ActionStep(type = "tick")))
        val ordinary = ActionStep(type = "repeat", parameters = mapOf("count" to "1000"), branches = listOf(ActionBranch("body", steps = List(51) { ActionStep(type = "tick") })))
        val result = engine.run(Shortcut(name = "Restored budget", steps = listOf(conditional, ordinary)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status); assertEquals("err_step_limit", (result.failure as Problem).key)
    }
    @Test fun conditionalBodiesCanCallChildrenBeyondTheOrdinaryBudget() = runTest {
        var count = 0
        val child = Shortcut(name = "Child", steps = listOf(ActionStep(type = "tick")))
        val backend = object : PlatformActions by PlatformActions.Unavailable {
            override suspend fun shortcutsForCall() = listOf(child)
        }
        val engine = ShortcutEngine(ActionRegistry(listOf(Handler("tick") { _, _ -> Value.Number((++count).toBigDecimal()) })), backend)
        val call = ActionStep(type = "run_shortcut", parameters = mapOf("shortcutUuid" to child.uuid))
        val repeat = loop(right = "6000", body = listOf(call))
        val result = engine.run(Shortcut(name = "Caller", steps = listOf(repeat)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("6000", result.output)
        assertEquals("6000", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun endConditionReferencesHaveAScopeAndSurviveXml() {
        val body = ActionStep(type = "text", parameters = mapOf("value" to "ok")); val repeat = loop(body = listOf(body))
        val flow = Shortcut(name = "Nested", steps = listOf(repeat))
        assertEquals(listOf(body.id), TreeEdits.availableAtRepeatEnd(flow.steps, repeat.id).map { it.id })
        val codec = XmlCodec(); val restored = codec.decode(codec.encode(flow))
        assertEquals(flow, restored); assertEquals("body", TreeEdits.duplicate(repeat).branches.single().id)
    }
    @Test fun variableBarDefaultsAndExplicitSwitchesAreIndependentOfMagicOutputs() {
        assertFalse(ActionOutputs.inVariableBar(ActionStep(type = "text")))
        assertTrue(ActionOutputs.inVariableBar(ActionStep(type = "share_input")))
        assertTrue(ActionOutputs.inVariableBar(ActionStep(type = "get_numbers_from_input")))
        assertFalse(ActionOutputs.inVariableBar(ActionStep(type = "share_input", parameters = mapOf("inVariableBar" to "false"))))
        assertTrue(ActionOutputs.inVariableBar(ActionStep(type = "number", parameters = mapOf("inVariableBar" to "true"))))
        assertFalse(ActionOutputs.inVariableBar(ActionStep(type = "share", parameters = mapOf("inVariableBar" to "true"))))
        assertEquals(listOf("repeatCount"), ActionOutputs.properties("repeat_until"))
    }
    @Test fun factorHandlerKeepsBooleanAndTextListTypes() = runTest {
        val previous = IntegerFactors.native
        try {
            IntegerFactors.native = { number, operation, powers -> assertEquals("6", number); if (operation == "is_prime") "false" else { assertFalse(powers); "1\n2\n3\n6" } }
            val prime = run(ActionStep(type = "factor_number", parameters = mapOf("source" to "6.0", "factorType" to "is_prime")))
            assertEquals(Value.BooleanValue(false), prime.steps.last().value)
            val divisors = run(ActionStep(type = "factor_number", parameters = mapOf("source" to "6", "factorType" to "divisors")))
            assertEquals(listOf("1", "2", "3", "6"), (divisors.steps.last().value as Value.ListValue).items.map { it.text() })
        } finally { IntegerFactors.native = previous }
    }
}
