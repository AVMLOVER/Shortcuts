package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EditorOperationsTest {
    private fun action(id: String) = ActionStep(id = id, type = "text", parameters = mapOf("value" to id))
    @Test fun placingAnActionBeforeOrAfterPreservesOtherActionsAndIds() {
        val steps = listOf(action("a"), action("b"), action("c"), action("d"))
        assertEquals(listOf("b", "c", "a", "d"), TreeEdits.place(steps, "a", "c", false).map { it.id })
        assertEquals(listOf("a", "d", "b", "c"), TreeEdits.place(steps, "d", "b", true).map { it.id })
        assertEquals(steps, TreeEdits.place(steps, "a", "a", false))
        assertEquals(steps, TreeEdits.place(steps, "a", "missing", false))
    }
    @Test fun movingAControlKeepsItsWholeSubtreeAndReferences() {
        val control = ActionStep(type = "repeat", parameters = mapOf("count" to "2"), branches = listOf(ActionBranch("body", steps = listOf(action("child")))))
        val result = TreeEdits.place(listOf(control, action("after")), control.id, "after", false)
        assertEquals(control, result.last()); assertEquals("child", result.last().branches.single().steps.single().id)
    }
    @Test fun nestedOrderingSupportsExplicitMovesAcrossBranches() {
        val conditional = ActionStep(type = "if", branches = listOf(ActionBranch("then", steps = listOf(action("a"), action("b"), action("c"))), ActionBranch("else", steps = listOf(action("other")))))
        val reordered = TreeEdits.place(listOf(conditional), "a", "c", false).single()
        assertEquals(listOf("b", "c", "a"), reordered.branches.first().steps.map { it.id })
        assertEquals(conditional.branches.last(), reordered.branches.last())
        val moved = TreeEdits.place(listOf(conditional), "a", "other", false).single()
        assertEquals(listOf("b", "c"), moved.branches.first().steps.map { it.id })
        assertEquals(listOf("other", "a"), moved.branches.last().steps.map { it.id })
        assertEquals(listOf(conditional), TreeEdits.place(listOf(conditional), conditional.id, "a", false))
    }
    @Test fun outputsInsideABranchExcludeIncompleteAncestorsAndOtherBranches() {
        val first = action("first")
        val conditional = ActionStep(id = "if", type = "if", branches = listOf(ActionBranch("then", steps = listOf(action("other"))), ActionBranch("else", steps = listOf(action("previous"), action("target")))))
        assertEquals(listOf("first", "previous"), TreeEdits.availableBefore(listOf(first, conditional), "target").map { it.id })
    }
    @Test fun outputsAfterAControlOfferTheControlResultAndExcludeDisabledSources() {
        val control = ActionStep(id = "if", type = "if", branches = listOf(ActionBranch("then", steps = listOf(action("child")))))
        val steps = listOf(action("disabled").copy(enabled = false), control, action("target"))
        assertEquals(listOf("if"), TreeEdits.availableBefore(steps, "target").map { it.id })
        assertTrue(TreeEdits.availableBefore(steps, "missing").isEmpty())
    }
    @Test fun multipleDictionaryRowsRetainTypesAndIgnoreUnusedRows() = runTest {
        val entries = listOf(EntrySpec("text", "first", "one\nline"), EntrySpec("number", "second", "2"), EntrySpec("boolean", "third", "true"), EntrySpec("auto", "", ""))
        val dictionary = ActionStep(type = "dictionary", parameters = writeEntrySpecs(emptyMap(), "entry", "entries", entries))
        val source = Shortcut(name = "Dictionary", steps = listOf(dictionary))
        val codec = XmlCodec(); val restored = codec.decode(codec.encode(source))
        assertEquals(4, readEntrySpecs(restored.steps.single().parameters, "entry", "entries").size)
        val result = ShortcutEngine().run(restored, ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        val value = result.steps.single().value as Value.Dictionary
        assertEquals(listOf("first", "second", "third"), value.entries.keys.toList()); assertEquals(Value.Number(2.toBigDecimal()), value.entries["second"])
    }
    @Test fun partiallyFilledAndDuplicateDictionaryRowsStillFailValidation() {
        for (entries in listOf(listOf(EntrySpec("text", "", "value")), listOf(EntrySpec("text", "same", "a"), EntrySpec("text", "same", "b")))) {
            val source = Shortcut(name = "Invalid", steps = listOf(ActionStep(type = "dictionary", parameters = writeEntrySpecs(emptyMap(), "entry", "entries", entries))))
            assertThrows(Problem::class.java) { ShortcutEngine().validate(source) }
        }
    }
}
