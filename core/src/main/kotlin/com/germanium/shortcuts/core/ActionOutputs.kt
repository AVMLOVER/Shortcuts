package com.germanium.shortcuts.core

object ActionOutputs {
    private val variables = setOf("create_variable", "set_variable", "get_variable", "append_variable")
    private val effects = setOf("delay", "quick_look", "show_result", "view_html", "notification", "reminder", "intent", "open_app", "open_url", "vibrate", "set_clipboard", "delete_file", "comment", "share", "wait_return", "return", "end_shortcut")
    fun produces(type: String) = type !in effects
    fun isVariableAction(type: String) = type in variables
    fun inVariableBar(step: ActionStep) = isVariableAction(step.type) || produces(step.type) && (step.parameters["inVariableBar"]?.let { it == "true" } ?: (step.type == "share_input" || step.type in listOf("get_text_from_input", "get_images_from_input", "get_urls_from_input", "get_files_from_input", "get_numbers_from_input")))
    val imageProperties = listOf("width", "height", "mimeType", "size", "name", "path")
    fun properties(type: String): List<String> = when (type) {
        "image_info" -> imageProperties
        "current_location" -> listOf("latitude", "longitude", "accuracy", "altitude", "timestamp", "provider")
        "repeat_until" -> listOf("repeatCount")
        else -> emptyList()
    }
}
