package com.germanium.shortcuts.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps a return event until Wait to Return consumes it, including a fast app switch. */
class ExternalReturnGate {
    private data class State(val token: Long = 0, val armed: Boolean = false, val left: Boolean = false, val returned: Boolean = false)
    private val state = MutableStateFlow(State())
    fun arm(): Long { val token = state.value.token + 1; state.value = State(token, true); return token }
    fun cancel(token: Long) { if (state.value.token == token) state.value = State(token) }
    fun leave() { val old = state.value; if (old.armed) state.value = old.copy(left = true) }
    fun resume() { val old = state.value; if (old.armed && old.left) state.value = old.copy(returned = true) }
    suspend fun await(): Boolean {
        val original = state.value
        if (!original.armed) return false
        val token = original.token
        // An Intent handled in the foreground need not cause a lifecycle departure.
        val departed = withTimeoutOrNull(3000) { state.first { it.token != token || !it.armed || it.left } }
        if (departed != null && departed.token == token && departed.armed) state.first { it.token != token || !it.armed || it.returned }
        cancel(token); return true
    }
}
