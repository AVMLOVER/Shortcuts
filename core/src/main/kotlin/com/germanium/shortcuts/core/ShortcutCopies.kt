package com.germanium.shortcuts.core

object ShortcutCopies {
    fun duplicate(source: Shortcut, now: Long = System.currentTimeMillis()) = source.copy(
        id = java.util.UUID.randomUUID().toString(),
        name = source.name.take(195) + " Copy",
        steps = TreeEdits.duplicateSteps(source.steps),
        createdAt = now,
        updatedAt = now,
    )
}
