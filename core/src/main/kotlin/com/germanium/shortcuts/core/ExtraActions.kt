package com.germanium.shortcuts.core

import java.time.*
import java.util.Locale
import com.google.re2j.Pattern
import com.google.re2j.Matcher

fun evaluateCondition(parameters: Map<String, String>, context: RunContext): Boolean {
    val left = context.resolve(parameters["left"] ?: "{{input}}")
    val operator = parameters.getOrDefault("operator", "equals")
    if (operator in ShortcutEngine.SOURCE_CONDITIONS) return (if (operator == "source_system_share") InputOrigin.SYSTEM_SHARE else InputOrigin.SHORTCUT) in context.originsOf(parameters["left"] ?: "{{input}}")
    val right = context.resolve(parameters["right"].orEmpty())
    val a = left.text(); val b = right.text()
    return when (parameters.getOrDefault("operator", "equals")) {
        "equals" -> if (left is Value.Number && right is Value.Number) left.value.compareTo(right.value) == 0 else a == b
        "not_equals" -> if (left is Value.Number && right is Value.Number) left.value.compareTo(right.value) != 0 else a != b
        "contains" -> a.contains(b)
        "not_contains" -> !a.contains(b)
        "greater" -> left.number() > right.number()
        "less" -> left.number() < right.number()
        "greater_equal" -> left.number() >= right.number()
        "less_equal" -> left.number() <= right.number()
        "is_empty" -> a.isEmpty()
        "is_not_empty" -> a.isNotEmpty()
        "is_true" -> a == "true"
        "matches" -> safeRegex(b).matcher(a).find()
        else -> throw Problem("err_condition")
    }
}

private fun safeRegex(pattern: String, ignoreCase: Boolean = false): Pattern {
    require(pattern.length <= 1024) { throw Problem("err_regex") }
    return try { Pattern.compile(pattern, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0) }
    catch (e: com.google.re2j.PatternSyntaxException) { throw Problem("err_regex") }
}

fun parseEventTime(source: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    source.toLongOrNull()?.let { return it }
    return runCatching { Instant.parse(source).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(source).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(source).atZone(zone).toInstant().toEpochMilli() }
        .recoverCatching { LocalDate.parse(source).atStartOfDay(zone).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(source, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli() }
        .recoverCatching { parseCurrentDateTime(source, zone) }
        .getOrElse { throw Problem("err_date") }
}

internal fun extraActions(platform: PlatformActions): List<ActionHandler> = listOf(
    Handler("replace_text", { require(it["find"].orEmpty().isNotEmpty()) { throw Problem("err_find") } }) { p, c ->
        val source = c.text(p, "source", "{{input}}")
        val find = c.text(p, "find"); require(find.isNotEmpty()) { throw Problem("err_find") }
        Value.Text(replaceLiteral(source, find, c.text(p, "replacement"), p["ignoreCase"] == "true"))
    },
    Handler("regex_replace", { if (!it["pattern"].orEmpty().contains("{{")) safeRegex(it["pattern"].orEmpty()) }) { p, c ->
        val source = c.text(p, "source", "{{input}}")
        require(source.length <= 16384) { throw Problem("err_regex_size") }
        val regex = safeRegex(c.text(p, "pattern"), p["ignoreCase"] == "true")
        val replacement = c.text(p, "replacement")
        val matcher = regex.matcher(source); val result = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(result, if (p["groups"] == "true") replacement else Matcher.quoteReplacement(replacement))
            require(result.length <= 262144) { throw Problem("err_size") }
        }
        matcher.appendTail(result); require(result.length <= 262144) { throw Problem("err_size") }
        Value.Text(result.toString())
    },
    Handler("match_text", { if (!it["pattern"].orEmpty().contains("{{")) safeRegex(it["pattern"].orEmpty()) }) { p, c ->
        val source = c.text(p, "source", "{{input}}"); require(source.length <= 16384) { throw Problem("err_regex_size") }
        val matcher = safeRegex(c.text(p, "pattern"), p["ignoreCase"] == "true").matcher(source)
        val matches = linkedMapOf<String, Value>()
        while (matcher.find()) { require(matches.size < 256) { throw Problem("err_size") }; matches[matches.size.toString()] = Value.Text(matcher.group()) }
        Value.ListValue(matches.values.toList())
    },
    Handler("text_case") { p, c -> Value.Text(when (p.getOrDefault("case", "upper")) { "upper" -> c.text(p, "source", "{{input}}").uppercase(Locale.ROOT); "lower" -> c.text(p, "source", "{{input}}").lowercase(Locale.ROOT); else -> throw Problem("err_invalid_data") }) },
    Handler("trim_text") { p, c -> Value.Text(c.text(p, "source", "{{input}}").trim()) },
    Handler("count_text") { p, c -> Value.Number(c.text(p, "source", "{{input}}").let { it.codePointCount(0, it.length) }.toBigDecimal()) },
    Handler("split_text", { require(it["separator"].orEmpty().isNotEmpty()) { throw Problem("err_find") } }) { p, c ->
        val source = c.text(p, "source", "{{input}}"); val separator = c.text(p, "separator"); require(separator.isNotEmpty()) { throw Problem("err_find") }
        val items = source.split(separator, limit = 257); require(items.size <= 256) { throw Problem("err_size") }
        Value.ListValue(items.map { Value.Text(it) })
    },
    Handler("join_text") { p, c ->
        val input = c.resolve(p["source"] ?: "{{input}}")
        val source = if (input is Value.ListValue) input.items else listOf(input)
        val separator = when (p["separatorType"]) { "newline" -> "\n"; "space" -> " "; "comma" -> ","; "none" -> ""; "custom", null -> c.text(p, "separator", ","); else -> throw Problem("err_invalid_data") }; val result = StringBuilder()
        source.forEachIndexed { index, item ->
            val text = item.text(); require(result.length.toLong() + text.length + (if (index > 0) separator.length else 0) <= 262144) { throw Problem("err_size") }
            if (index > 0) result.append(separator); result.append(text)
        }
        Value.Text(result.toString())
    },
    Handler("quick_look") { p, c -> val value = c.resolve(p["value"] ?: "{{input}}"); platform.quickLook(value); value },
    Handler("notification") { p, c -> platform.postNotification(c.text(p, "title", "Shortcuts"), c.resolve(p["body"] ?: "{{input}}")); c.value },
    Handler("calendar", { require(it["title"].orEmpty().isNotBlank() && it["start"].orEmpty().isNotBlank() && it["end"].orEmpty().isNotBlank()) { throw Problem("err_calendar") } }) { p, c ->
        val start = parseEventTime(c.text(p, "start")); val end = parseEventTime(c.text(p, "end")); require(start >= 0 && end > start) { throw Problem("err_date") }
        require(c.text(p, "title").isNotBlank()) { throw Problem("err_calendar") }
        val reminder = p["reminderMinutes"]?.takeIf(String::isNotBlank)?.let { c.render(it).toIntOrNull() ?: throw Problem("err_calendar") }
        require(reminder == null || reminder in 0..10080) { throw Problem("err_calendar") }
        val calendar = c.text(p, "calendarId").takeIf(String::isNotBlank)?.let { it.toLongOrNull() ?: throw Problem("err_calendar") }
        Value.Text(platform.createCalendarEvent(CalendarEventSpec(c.text(p, "title"), start, end, c.text(p, "description"), c.text(p, "location"), calendar, reminder)))
    },
    Handler("install_apk", { require(it["uri"].orEmpty().startsWith("content://")) { throw Problem("err_apk") } }) { p, c -> Value.Text(platform.installApk(p.getValue("uri"), c.mode)) },
)

private fun replaceLiteral(source: String, find: String, replacement: String, ignoreCase: Boolean): String {
    val result = StringBuilder(); var cursor = 0
    while (true) {
        val at = source.indexOf(find, cursor, ignoreCase)
        if (at < 0) break
        require(result.length.toLong() + at - cursor + replacement.length <= 262144) { throw Problem("err_size") }
        result.append(source, cursor, at).append(replacement); cursor = at + find.length
    }
    require(result.length.toLong() + source.length - cursor <= 262144) { throw Problem("err_size") }
    return result.append(source, cursor, source.length).toString()
}
