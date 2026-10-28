package com.germanium.shortcuts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DragScope(val owner: String?, val branch: String?)
internal data class DragItem(val id: String, val scope: DragScope, val title: String)
internal data class DragDestination(val source: String, val target: String, val before: Boolean)

/** The viewport owns the gesture so auto scrolling can recycle the source row without cancelling the drag. */
internal class EditorDragState {
    var source by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var destination by mutableStateOf<DragDestination?>(null)
    var origin = Offset.Zero
    var height = 0f
    var gapBounds: Rect? = null
    var items: Map<String, DragItem> = emptyMap()
    val handles = mutableMapOf<String, Rect>()
    fun hit(position: Offset) = handles.entries.firstOrNull { it.value.contains(position) }?.key
    fun start(id: String, position: Offset) { source = id; update(position) }
    fun update(position: Offset) { pointer = position; refreshTarget() }
    fun refreshTarget() {
        val item = items[source] ?: return
        if (destination != null && gapBounds?.contains(pointer) == true) return
        val nearest = handles.entries.filter { it.key != item.id && items[it.key]?.scope == item.scope }
            .minByOrNull { abs(it.value.center.y - pointer.y) }
        destination = nearest?.let { DragDestination(item.id, it.key, pointer.y < it.value.center.y) }
    }
    fun clear() { source = null; destination = null; gapBounds = null }
}

@Composable internal fun DragInsertionGap(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = expandVertically(tween(140)) + fadeIn(tween(140)), exit = shrinkVertically(tween(140)) + fadeOut(tween(100))) {
        Surface(modifier.fillMaxWidth().padding(vertical = 6.dp).height(56.dp).testTag("drag_insertion_gap"), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(R.drawable.ic_add); Text(t("drop_action_here"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

internal fun Modifier.editorDragGestures(state: EditorDragState, enabled: Boolean, started: () -> Unit, finished: (DragDestination) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val id = state.hit(down.position) ?: return@awaitEachGesture
            down.consume()
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    pointer.consume()
                    if (!pointer.pressed) {
                        if (dragging) { state.update(pointer.position); state.destination?.let(finished) }
                        break
                    }
                    if (!dragging && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        dragging = true; started(); state.start(id, pointer.position)
                    }
                    if (dragging) state.update(pointer.position)
                }
            } finally { state.clear() }
        }
    }

@Composable internal fun DragAutoScroll(state: EditorDragState, list: LazyListState) {
    LaunchedEffect(state.source) {
        if (state.source == null) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                val elapsed = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                previous = now
                val edge = 180f.coerceAtMost(state.height / 3f)
                val speed = when {
                    state.pointer.y < edge -> -((edge - state.pointer.y) / edge).coerceIn(0f, 1f)
                    state.pointer.y > state.height - edge -> ((state.pointer.y - state.height + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                state.refreshTarget()
                speed * 1800f * elapsed
            }.let { delta -> if (delta != 0f) list.scrollBy(delta) }
        }
    }
}

@Composable internal fun DragPreview(state: EditorDragState) {
    val source = state.items[state.source] ?: return
    val destination = state.destination
    Surface(Modifier.offset { IntOffset(12.dp.roundToPx(), (state.pointer.y - 68.dp.toPx()).roundToInt().coerceAtLeast(0)) }
        .fillMaxWidth().padding(end = 24.dp).zIndex(10f).testTag("drag_preview"), shape = MaterialTheme.shapes.large, shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(source.title, style = MaterialTheme.typography.titleMedium)
            Text(if (destination == null) t("drag_scope_hint") else t(if (destination.before) "place_before" else "place_after", state.items[destination.target]?.title.orEmpty()), style = MaterialTheme.typography.bodySmall)
        }
    }
}
