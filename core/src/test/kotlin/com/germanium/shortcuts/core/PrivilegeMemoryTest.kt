package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class PrivilegeMemoryTest {
    private val stored = mutableMapOf<ExecutionMode, Boolean>()
    private fun memory() = PrivilegeMemory({ stored[it] == true }, { mode, granted -> stored[mode] = granted })

    @Test fun rootAndShizukuGrantsCoexistAndSurviveReload() {
        memory().record(ExecutionMode.ROOT, true)
        memory().record(ExecutionMode.SHIZUKU, true)
        val reloaded = memory()
        assertTrue(reloaded.granted(ExecutionMode.ROOT))
        assertTrue(reloaded.granted(ExecutionMode.SHIZUKU))
        assertTrue(reloaded.granted(ExecutionMode.STANDARD))
    }

    @Test fun disconnectingAServiceRetainsItsGrantUntilAnActualRejectionIsKnown() {
        val memory = memory()
        memory.record(ExecutionMode.ROOT, true)
        memory.record(ExecutionMode.SHIZUKU, true)
        memory.record(ExecutionMode.SHIZUKU, null)
        assertTrue(memory.granted(ExecutionMode.SHIZUKU))
        memory.record(ExecutionMode.SHIZUKU, false)
        assertFalse(memory.granted(ExecutionMode.SHIZUKU))
        assertTrue(memory.granted(ExecutionMode.ROOT))
    }

    @Test fun selectingStandardDoesNotEraseEitherGrantOrCreateAnAuthorization() {
        val memory = memory()
        assertFalse(memory.granted(ExecutionMode.ROOT))
        assertFalse(memory.granted(ExecutionMode.SHIZUKU))
        memory.record(ExecutionMode.ROOT, true)
        memory.record(ExecutionMode.STANDARD, false)
        assertTrue(memory.granted(ExecutionMode.ROOT))
        assertFalse(memory.granted(ExecutionMode.SHIZUKU))
        assertFalse(stored.containsKey(ExecutionMode.STANDARD))
    }
}
