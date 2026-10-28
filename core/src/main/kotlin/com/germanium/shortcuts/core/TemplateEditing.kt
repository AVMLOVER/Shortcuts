package com.germanium.shortcuts.core

data class ReferenceToken(val start: Int, val end: Int, val name: String)
data class TemplateEdit(val text: String, val start: Int, val end: Int)

/** UTF-16 offsets, matching Android/Compose selections. References remain ordinary XML template strings. */
object TemplateEditing {
    private val pattern = Regex("\\{\\{([^{}]+)\\}\\}")
    fun tokens(text: String): List<ReferenceToken> = pattern.findAll(text).map { ReferenceToken(it.range.first, it.range.last + 1, it.groupValues[1].trim()) }.toList()
    fun snap(text: String, start: Int, end: Int): TemplateEdit {
        val tokens = tokens(text)
        fun edge(value: Int, upper: Boolean): Int {
            val offset = value.coerceIn(0, text.length)
            val token = tokens.firstOrNull { offset > it.start && offset < it.end } ?: return offset
            return if (start == end) { if (offset - token.start < token.end - offset) token.start else token.end }
                else if (upper) token.end else token.start
        }
        val forward = start <= end
        return TemplateEdit(text, edge(start, !forward), edge(end, forward))
    }
    fun replace(text: String, start: Int, end: Int, replacement: String): TemplateEdit {
        val selection = snap(text, start, end)
        val first = minOf(selection.start, selection.end); val last = maxOf(selection.start, selection.end)
        val result = text.substring(0, first) + replacement + text.substring(last)
        val caret = first + replacement.length
        return TemplateEdit(result, caret, caret)
    }
    /** Expand any keyboard/IME edit touching a reference to its complete range. */
    fun reconcile(previous: String, incoming: String, selectionStart: Int, selectionEnd: Int): TemplateEdit {
        if (previous == incoming) return snap(incoming, selectionStart, selectionEnd)
        var prefix = 0
        while (prefix < minOf(previous.length, incoming.length) && previous[prefix] == incoming[prefix]) prefix++
        var suffix = 0
        while (suffix < previous.length - prefix && suffix < incoming.length - prefix && previous[previous.lastIndex - suffix] == incoming[incoming.lastIndex - suffix]) suffix++
        val oldEnd = previous.length - suffix
        var first = prefix; var last = oldEnd
        for (token in tokens(previous)) {
            val affected = if (prefix == oldEnd) prefix > token.start && prefix < token.end else token.start < oldEnd && token.end > prefix
            if (affected) { first = minOf(first, token.start); last = maxOf(last, token.end) }
        }
        if (first == prefix && last == oldEnd) return snap(incoming, selectionStart, selectionEnd)
        val inserted = incoming.substring(prefix, incoming.length - suffix)
        val result = previous.substring(0, first) + inserted + previous.substring(last)
        return TemplateEdit(result, first + inserted.length, first + inserted.length)
    }
}
