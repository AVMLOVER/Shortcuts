package com.germanium.shortcuts

import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.View

internal data class LibraryActionDrag(val type: String)
internal object LibraryDragSession {
    var active: LibraryActionDrag? = null
    var nonce: String = ""
    fun matches(data: ClipData?) = data?.itemCount == 1 && data.getItemAt(0).text?.toString() == "$nonce:${active?.type}"
    fun clear() { active = null; nonce = "" }
}
internal fun startLibraryDrag(view: View, type: String, title: String): Boolean {
    val density = view.resources.displayMetrics.density
    val width = (240 * density).toInt(); val height = (64 * density).toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shadow = object : View.DragShadowBuilder() {
        override fun onProvideShadowMetrics(size: Point, touch: Point) { size.set(width, height); touch.set(width / 2, height / 2) }
        override fun onDrawShadow(canvas: Canvas) {
            paint.color = androidx.core.content.ContextCompat.getColor(view.context, R.color.widget_surface)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 24 * density, 24 * density, paint)
            paint.color = androidx.core.content.ContextCompat.getColor(view.context, R.color.widget_on_surface); paint.textSize = 16 * density
            canvas.drawText(title.take(22), 16 * density, 38 * density, paint)
        }
    }
    val token = LibraryActionDrag(type)
    LibraryDragSession.active = token; LibraryDragSession.nonce = java.util.UUID.randomUUID().toString()
    val started = view.startDragAndDrop(ClipData("Shortcuts action", arrayOf("application/vnd.germanium.shortcuts.action"), ClipData.Item("${LibraryDragSession.nonce}:$type")), shadow, token, View.DRAG_FLAG_GLOBAL)
    if (!started) LibraryDragSession.clear()
    return started
}
