package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RereleaseTest {
    private fun number(id: String, value: String) = ActionStep(id = id, type = "number", parameters = mapOf("value" to value))
    private fun divisionFlow(writer: String): Shortcut {
        val divide = ActionStep(id = "divide", type = "calculate", parameters = mapOf("left" to "{{counter}}", "operation" to "/", "right" to "3"))
        val update = ActionStep(id = "update", type = writer, parameters = mapOf("name" to "counter", "value" to "{{@divide}}"))
        return Shortcut(name = "Division", steps = listOf(number("number", "111"),
            ActionStep(type = "create_variable", parameters = mapOf("name" to "counter", "valueType" to "number", "value" to "{{@number}}")),
            ActionStep(id = "loop", type = "repeat_until", parameters = mapOf("left" to "{{counter}}", "operator" to "less", "right" to "1"), branches = listOf(ActionBranch("body", steps = listOf(ActionStep(type = "get_variable", parameters = mapOf("name" to "counter")), divide, update))))))
    }
    @Test fun replacingTheCounterCompletesConditionalRepeatWithANumberAndCount() = runTest {
        val flow = divisionFlow("set_variable")
        val result = ShortcutEngine().run(XmlCodec().decode(XmlCodec().encode(flow)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertTrue(result.variables["counter"] is Value.Number)
        assertTrue(result.variables.getValue("counter").number() < 1.toBigDecimal())
        assertEquals("5", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun appendKeepsThePreviousValueAndExplainsWhyAListCannotBeComparedNumerically() = runTest {
        val result = ShortcutEngine().run(divisionFlow("append_variable"), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("err_number_list", (result.failure as Problem).key)
        assertEquals(listOf("111", "37"), (result.variables.getValue("counter") as Value.ListValue).items.map { it.text() })
    }
    @Test fun variableActionsIgnoreLegacyDisabledSuggestionFlags() {
        listOf("create_variable", "set_variable", "get_variable", "append_variable").forEach {
            assertTrue(ActionOutputs.inVariableBar(ActionStep(type = it, parameters = mapOf("inVariableBar" to "false"))))
        }
    }
    @Test fun getVariableAcceptsMagicOutputsAndMetadataWithoutLosingTypes() = runTest {
        val loop = divisionFlow("set_variable")
        val get = ActionStep(type = "get_variable", parameters = mapOf("name" to "@loop.repeatCount"))
        val result = ShortcutEngine().run(loop.copy(steps = loop.steps + get), ExecutionMode.STANDARD)
        assertEquals(Value.Number(5.toBigDecimal()), result.steps.last().value)
    }
    private fun group(type: String): ActionStep = ActionStep(id = "group", type = type, branches = listOf(ActionBranch(if (type == "if") "then" else if (type == "menu") "choice" else "body", label = "Choice", steps = listOf(number("child", "3")))))
    @Test fun actionsCanMoveBeforeAndAfterEveryKindOfControl() {
        listOf("if", "menu", "repeat", "repeat_each", "repeat_until").forEach { type ->
            val parent = group(type); val source = listOf(parent, number("after", "4"))
            val before = TreeEdits.place(source, "child", "group", true)
            assertEquals(listOf("child", "group", "after"), before.map { it.id }); assertTrue(before[1].branches.single().steps.isEmpty())
            val after = TreeEdits.place(source, "child", "group", false)
            assertEquals(listOf("group", "child", "after"), after.map { it.id })
            val inside = TreeEdits.placeInBranch(after, "child", "group", parent.branches.single().id)
            assertEquals(source, inside)
            assertEquals(source, TreeEdits.placeInBranch(source, "group", "group", parent.branches.single().id))
            assertEquals(source, TreeEdits.place(source, "group", "child", true))
        }
    }
    @Test fun removingAControlRemovesAllDescendantsForBothDeleteEntrypoints() {
        val nested = group("menu").copy(branches = listOf(ActionBranch("choice", steps = listOf(group("if").copy(id = "nested")))))
        val steps = listOf(number("before", "1"), nested, number("after", "2"))
        assertEquals(listOf("before", "after"), allSteps(TreeEdits.remove(steps, nested.id)).map { it.id })
    }
    @Test fun copyingAWholeShortcutRemapsReferencesAcrossRootActionsAndNestedBranches() = runTest {
        val source = divisionFlow("set_variable").copy(icon = "star", color = "#206080", folderId = "folder")
        val copy = ShortcutCopies.duplicate(source, 1234)
        assertNotEquals(source.id, copy.id); assertEquals("Division Copy", copy.name)
        assertEquals(source.icon, copy.icon); assertEquals(source.color, copy.color); assertEquals(source.folderId, copy.folderId)
        assertEquals(1234L, copy.createdAt); assertEquals(1234L, copy.updatedAt)
        assertTrue(allSteps(source.steps).map { it.id }.toSet().intersect(allSteps(copy.steps).map { it.id }.toSet()).isEmpty())
        val result = ShortcutEngine().run(copy, ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("5", result.steps.last().properties["repeatCount"]?.text())
        val get = ActionStep(type = "get_variable", parameters = mapOf("name" to "@number"))
        val two = ShortcutCopies.duplicate(source.copy(steps = source.steps.take(1) + get))
        assertEquals("@${two.steps.first().id}", two.steps.last().parameters["name"])
        assertEquals("111", ShortcutEngine().run(two, ExecutionMode.STANDARD).output)
        assertEquals(source, XmlCodec().decode(XmlCodec().encode(source)))
    }
    @Test fun copyingALongNameStaysWithinTheXmlNameLimit() {
        assertEquals(200, ShortcutCopies.duplicate(Shortcut(name = "A".repeat(200), steps = listOf(number("n", "1")))).name.length)
    }
}
