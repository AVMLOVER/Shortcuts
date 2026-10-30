package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class ActionLibraryNavigationTest {
    @Test fun jumpingUsesTheFullLibraryWithTheRequestedCategoryOrder() {
        val sections = ActionLibraryNavigation.sections(listOf("images", "files", "control", "basic", "apps", "math", "basic", "system", "variables", "text", "images", "dates"))
        assertEquals(listOf("basic", "control", "variables", "text", "math", "system", "files", "apps", "images", "dates"), sections.map { it.group })
        assertEquals(listOf(0, 3, 5, 7, 9, 11, 13, 15, 17, 20), sections.map { it.headerIndex })
        assertEquals(12, sections.sumOf { it.actionCount })
    }

    @Test fun scrollingAcrossHeadingAndActionBoundariesTracksTheVisibleCategory() {
        val sections = ActionLibraryNavigation.sections(listOf("basic", "basic", "control", "text"))
        assertEquals("basic", ActionLibraryNavigation.currentGroup(sections, 0, false))
        assertEquals("basic", ActionLibraryNavigation.currentGroup(sections, 2, false))
        assertEquals("control", ActionLibraryNavigation.currentGroup(sections, 3, false))
        assertEquals("control", ActionLibraryNavigation.currentGroup(sections, 4, false))
        assertEquals("text", ActionLibraryNavigation.currentGroup(sections, 5, false))
    }

    @Test fun aShortLastCategoryIsSelectedAtTheBottomEvenIfItsHeadingCannotReachTheTop() {
        val sections = ActionLibraryNavigation.sections(List(20) { "basic" } + "files")
        assertEquals("basic", ActionLibraryNavigation.currentGroup(sections, 18, false))
        assertEquals("files", ActionLibraryNavigation.currentGroup(sections, 18, true))
    }

    @Test fun searchCanOmitCategoriesWithoutLeavingStaleIndices() {
        val sections = ActionLibraryNavigation.sections(listOf("files", "text", "files"))
        assertEquals(listOf("text", "files"), sections.map { it.group })
        assertEquals(2, sections.last().headerIndex)
        assertEquals("text", ActionLibraryNavigation.currentGroup(sections, 0, false))
        assertEquals("files", ActionLibraryNavigation.currentGroup(sections, 2, false))
        assertNull(ActionLibraryNavigation.currentGroup(emptyList(), 0, false))
        assertNull(ActionLibraryNavigation.currentGroup(emptyList(), 0, true))
    }
}
