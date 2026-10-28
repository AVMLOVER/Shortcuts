package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class TemplateEditingTest {
    @Test fun parsesMagicAndNamedReferencesWithUtf16Offsets() {
        val text = "😀{{数量}}/{{@source}}"
        assertEquals(listOf(ReferenceToken(2, 8, "数量"), ReferenceToken(9, 20, "@source")), TemplateEditing.tokens(text))
    }
    @Test fun keyboardBackspaceDeletesTheWholeReference() {
        val result = TemplateEditing.reconcile("a{{value}}b", "a{{value}b", 9, 9)
        assertEquals(TemplateEdit("ab", 1, 1), result)
    }
    @Test fun forwardDeleteAlsoDeletesTheWholeReference() {
        assertEquals(TemplateEdit("ab", 1, 1), TemplateEditing.reconcile("a{{value}}b", "a{value}}b", 1, 1))
    }
    @Test fun deletingAnAdjacentReferencePreservesItsNeighbor() {
        val before = "{{first}}{{second}}"
        val incoming = "{{first}{{second}}"
        assertEquals("{{second}}", TemplateEditing.reconcile(before, incoming, 8, 8).text)
    }
    @Test fun textOutsideReferencesIsEditedNormally() {
        val result = TemplateEditing.reconcile("hello {{value}}!", "hell {{value}}!", 4, 4)
        assertEquals(TemplateEdit("hell {{value}}!", 4, 4), result)
    }
    @Test fun selectedTextAndPartialVariablesAreReplacedAtomically() {
        assertEquals(TemplateEdit("start X end", 7, 7), TemplateEditing.replace("start {{value}} end", 8, 12, "X"))
    }
    @Test fun insertionUsesTheOriginalCaretPosition() {
        val token = "{{@source}}"
        assertEquals(TemplateEdit("A${token}B", 1 + token.length, 1 + token.length), TemplateEditing.replace("AB", 1, 1, token))
    }
    @Test fun replacingASelectedVariableRetainsSurroundingText() {
        assertEquals("A{{new}}B", TemplateEditing.replace("A{{old}}B", 1, 8, "{{new}}").text)
    }
    @Test fun reversedSelectionsCoverTheCompleteReference() {
        val text = "a{{value}}b"
        assertEquals(TemplateEdit(text, 10, 1), TemplateEditing.snap(text, 8, 3))
    }
    @Test fun incompleteBracesRemainOrdinaryTextUntilAReferenceIsComplete() {
        assertTrue(TemplateEditing.tokens("x{{unfinished}").isEmpty())
        assertEquals("{{name}}", TemplateEditing.reconcile("{{name}", "{{name}}", 8, 8).text)
    }
    @Test fun pasteAcrossReferencesKeepsOnlyThePastedContent() {
        assertEquals("prefix replacement suffix", TemplateEditing.replace("prefix {{one}} {{two}} suffix", 7, 22, "replacement").text)
    }
    @Test fun updatingALeafPreservesUnrelatedObjectIdentity() {
        val steps = List(1000) { ActionStep(type = "text", parameters = mapOf("value" to "$it")) }
        val changed = TreeEdits.update(steps, steps[500].id) { it.copy(parameters = mapOf("value" to "edited")) }
        assertSame(steps[0], changed[0]); assertSame(steps[999], changed[999]); assertNotSame(steps[500], changed[500])
        assertSame(steps, TreeEdits.update(steps, "missing") { error("Must not run") })
    }
}
