package com.germanium.shortcuts

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class Interaction {
    val id = java.util.UUID.randomUUID().toString()
    var launched: Boolean = false
    class Menu(val title: String, val items: List<Pair<String, String>>) : Interaction()
    class QuickLook(val text: String) : Interaction()
    class Permissions(val permissions: Array<String>) : Interaction()
    class Launch(val intent: Intent) : Interaction()
    class Input(val title: String, val prompt: String, val initial: String, val type: String) : Interaction()
    class Checklist(val title: String, val items: List<Pair<String, String>>) : Interaction()
    class Foreground : Interaction()
    class Capture(val folder: String, val filename: String) : Interaction()
    class ResultDialog(val title: String, val body: String, val allowCancel: Boolean) : Interaction()
    class Html(val html: String) : Interaction()
    class PickFile(val imageOnly: Boolean) : Interaction()
    class TakePhoto : Interaction()
    class ImageEdit(val operation: String, val source: com.germanium.shortcuts.core.Value, val options: Map<String, String>) : Interaction()
    class SavePdf(val filename: String) : Interaction()
    val result = CompletableDeferred<String?>()
}
class Interactions {
    var onPending: ((Interaction) -> Unit)? = null
    var onResolved: (() -> Unit)? = null
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<Interaction?>(null)
    val current = mutable.asStateFlow()
    suspend fun ask(request: Interaction): String? = mutex.withLock {
        mutable.value = request
        try { onPending?.invoke(request); request.result.await() } finally { if (mutable.value === request) { mutable.value = null; onResolved?.invoke() } }
    }
    fun reply(request: Interaction, answer: String?) { if (mutable.value === request) request.result.complete(answer) }
}
