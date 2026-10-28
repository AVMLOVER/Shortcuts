package com.germanium.shortcuts.core

object ActionOutputs {
    private val effects = setOf("delay", "quick_look", "show_result", "view_html", "notification", "reminder", "intent", "open_app", "open_url", "vibrate", "set_clipboard", "delete_file")
    fun produces(type: String) = type !in effects
    val imageProperties = listOf("width", "height", "mimeType", "size", "name", "path")
    fun properties(type: String): List<String> = when (type) {
        "image_info" -> imageProperties
        "current_location" -> listOf("latitude", "longitude", "accuracy", "altitude", "timestamp", "provider")
        else -> emptyList()
    }
}
