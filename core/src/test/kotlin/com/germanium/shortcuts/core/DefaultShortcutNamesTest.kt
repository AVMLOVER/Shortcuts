package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class DefaultShortcutNamesTest {
    @Test fun defaultsFollowTheRequestedSequenceAcrossOpenEditors(){
        val names=DefaultShortcutNames()
        assertEquals("新快捷指令",names.reserve("a","新快捷指令",emptyList()))
        assertEquals("新快捷指令1",names.reserve("b","新快捷指令",emptyList()))
        assertEquals("新快捷指令2",names.reserve("c","新快捷指令",emptyList()))
        assertEquals("新快捷指令1",names.reserve("b","新快捷指令",emptyList()))
    }
    @Test fun savedNamesInEveryFolderAndImportedWhitespaceAreTakenIntoAccount(){
        assertEquals("新快捷指令3",DefaultShortcutNames.next("新快捷指令",listOf("新快捷指令","新快捷指令1","新快捷指令2 ")))
        assertEquals("新快捷指令2",DefaultShortcutNames.next("新快捷指令",listOf("新快捷指令","新快捷指令1","新快捷指令3")))
    }
    @Test fun cancelledEditorsReleaseTheirReservationButSavedNamesStillOccupyTheSlot(){
        val names=DefaultShortcutNames();names.reserve("a","New shortcut",emptyList());names.release("a")
        assertEquals("New shortcut",names.reserve("b","New shortcut",emptyList()));names.release("b")
        assertEquals("New shortcut1",names.reserve("c","New shortcut",listOf("New shortcut")))
    }
    @Test fun theCurrentLocalizedBaseIsUsedWithoutRenamingExistingWorkflows(){
        val existing=listOf("New shortcut","新快捷指令","Neuer Kurzbefehl")
        assertEquals("Neuer Kurzbefehl1",DefaultShortcutNames.next("Neuer Kurzbefehl",existing))
        assertEquals("新快捷指令1",DefaultShortcutNames.next("新快捷指令",existing));assertEquals(3,existing.size)
    }
}
