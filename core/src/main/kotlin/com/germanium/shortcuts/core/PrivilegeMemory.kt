package com.germanium.shortcuts.core

/** Local authorization history is separate from the currently selected execution mode. */
class PrivilegeMemory(private val read: (ExecutionMode) -> Boolean, private val write: (ExecutionMode, Boolean) -> Unit) {
    fun granted(mode: ExecutionMode): Boolean = mode == ExecutionMode.STANDARD || read(mode)

    // A disconnected service gives no new permission verdict; retain its last known grant.
    fun record(mode: ExecutionMode, granted: Boolean?) {
        if (mode != ExecutionMode.STANDARD && granted != null && read(mode) != granted) write(mode, granted)
    }
}
