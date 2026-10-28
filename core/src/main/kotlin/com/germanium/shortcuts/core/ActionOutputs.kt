package com.germanium.shortcuts.core

object ActionOutputs {
    private val effects = setOf("delay", "quick_look", "show_result", "view_html", "notification", "reminder", "intent", "open_app", "open_url", "vibrate", "set_clipboard", "delete_file", "comment", "share", "wait_return")
    fun produces(type: String) = type !in effects
    fun inVariableBar(step: ActionStep) = produces(step.type) && (step.parameters["inVariableBar"]?.let { it == "true" } ?: (step.type == "share_input" || step.type in listOf("get_text_from_input", "get_images_from_input", "get_urls_from_input", "get_files_from_input", "get_numbers_from_input")))
    val imageProperties = listOf("width", "height", "mimeType", "size", "name", "path")
    fun properties(type: String): List<String> = when (type) {
        "image_info" -> imageProperties
        "current_location" -> listOf("latitude", "longitude", "accuracy", "altitude", "timestamp", "provider")
        "repeat_until" -> listOf("repeatCount")
        else -> emptyList()
    }
}
