package com.germanium.shortcuts.core

data class ActionLibrarySection(val group: String, val headerIndex: Int, val actionCount: Int)

/** Indices include one heading per section, matching the continuous action library. */
object ActionLibraryNavigation {
    private val preferredOrder = listOf("basic", "control", "variables", "text", "math", "system", "files", "apps")

    fun sections(actionGroups: List<String>): List<ActionLibrarySection> {
        val counts = actionGroups.groupingBy { it }.eachCount()
        var index = 0
        return (preferredOrder + counts.keys).distinct().mapNotNull { group ->
            counts[group]?.let { count ->
                ActionLibrarySection(group, index, count).also { index += count + 1 }
            }
        }
    }

    fun currentGroup(sections: List<ActionLibrarySection>, firstVisibleIndex: Int, atEnd: Boolean): String? =
        if (atEnd) sections.lastOrNull()?.group
        else sections.lastOrNull { it.headerIndex <= firstVisibleIndex }?.group
}
