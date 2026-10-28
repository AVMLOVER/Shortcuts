package com.germanium.shortcuts.core

/** Bounded immutable snapshots. Consecutive typing in one field is one undo group. */
class EditHistory<T : Any>(private val capacity: Int = 100, private val mergeWindowMs: Long = 700) {
    init { require(capacity > 0 && mergeWindowMs >= 0) }
    private val past = java.util.ArrayDeque<T>()
    private val future = java.util.ArrayDeque<T>()
    private var lastGroup: String? = null
    private var lastChange = 0L
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun record(previous: T, group: String? = null, now: Long = System.currentTimeMillis()) {
        val merge = group != null && group == lastGroup && now - lastChange in 0..mergeWindowMs && future.isEmpty()
        if (!merge) { past.addLast(previous); while (past.size > capacity) past.removeFirst() }
        future.clear(); lastGroup = group; lastChange = now
    }
    fun undo(current: T): T? {
        if (past.isEmpty()) return null
        future.addLast(current); lastGroup = null
        return past.removeLast()
    }
    fun redo(current: T): T? {
        if (future.isEmpty()) return null
        past.addLast(current); lastGroup = null
        return future.removeLast()
    }
    fun clear() { past.clear(); future.clear(); lastGroup = null }
}
