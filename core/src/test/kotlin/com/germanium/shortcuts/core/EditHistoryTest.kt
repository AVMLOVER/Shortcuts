package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    @Test fun typingInOneFieldCoalescesButDifferentFieldsStaySeparate() {
        val h = EditHistory<String>()
        h.record("a", "text", 1000); h.record("ab", "text", 1100); h.record("abc", "name", 1200)
        assertEquals("abc",h.undo("new name")); assertEquals("a",h.undo("abc")); assertFalse(h.canUndo)
        assertEquals("abc",h.redo("a")); assertEquals("new name",h.redo("abc")); assertFalse(h.canRedo)
    }
    @Test fun editingAfterUndoStartsANewBranchAndDiscardsRedo() {
        val h = EditHistory<String>(); h.record("a", "text", 1000); assertEquals("a",h.undo("ab"))
        h.record("a", "text", 1100); assertFalse(h.canRedo); assertNull(h.redo("ac")); assertEquals("a",h.undo("ac"))
    }
    @Test fun historyIsBoundedAndCanBeCleared() {
        val h = EditHistory<Int>(2); h.record(0); h.record(1); h.record(2)
        assertEquals(2,h.undo(3)); assertEquals(1,h.undo(2)); assertNull(h.undo(1)); assertTrue(h.canRedo)
        h.clear(); assertFalse(h.canUndo); assertFalse(h.canRedo)
    }
    @Test fun structuralSnapshotsRestoreEveryChildAndItsStableReferences() {
        val child = ActionStep(type="number",parameters=mapOf("value" to "3"))
        val group = ActionStep(type="repeat_until",branches=listOf(ActionBranch("body",steps=listOf(child))))
        val after = ActionStep(type="text",parameters=mapOf("value" to "{{@${child.id}}}"))
        val before = listOf(group,after); val h = EditHistory<List<ActionStep>>()
        h.record(before); val removed=TreeEdits.remove(before,group.id)
        assertEquals(before,h.undo(removed)); assertEquals(removed,h.redo(before)); assertEquals(2,allSteps(before).count { it.id in listOf(child.id,group.id) })
    }
}
