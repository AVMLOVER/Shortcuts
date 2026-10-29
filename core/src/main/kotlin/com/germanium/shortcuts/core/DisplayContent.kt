package com.germanium.shortcuts.core

/** Retain file references and typed lists for all display surfaces. */
object DisplayContent {
    fun items(source: Value): List<Value> = if (ContentInput.isPayload(source)) {
        val text = ContentInput.field(source, "text")?.takeIf { it.text().isNotEmpty() }
        (listOfNotNull(text) + ContentInput.flatten(ContentInput.field(source, "images") ?: Value.ListValue(emptyList())) +
            ContentInput.flatten(ContentInput.field(source, "files") ?: Value.ListValue(emptyList()))).distinct()
    } else ContentInput.flatten(source)
}
