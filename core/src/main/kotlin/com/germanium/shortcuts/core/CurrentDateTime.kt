package com.germanium.shortcuts.core

import java.time.*
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.util.Locale

/** Numeric regional date order with a four-digit year and a stable seconds-resolution time. */
fun defaultCurrentDateTimePattern(locale: Locale = Locale.getDefault()): String {
    val pattern = if (locale.language == "zh" && locale.script != "Hant" && locale.country in listOf("", "CN", "SG")) "yyyy-MM-dd"
        else DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
    val date = buildString {
        var index = 0; var quoted = false
        while (index < pattern.length) {
            val char = pattern[index]
            if (char == '\'') { append(char); index++; if (pattern.getOrNull(index) == '\'') { append('\''); index++ } else quoted = !quoted }
            else if (!quoted && char in "yMuLd") {
                val start = index; while (pattern.getOrNull(index) == char) index++
                append(when { char in "yu" -> "yyyy"; char == 'd' -> "dd"; index - start <= 2 -> "MM"; else -> pattern.substring(start, index) })
            } else { append(char); index++ }
        }
    }
    return "$date HH:mm:ss"
}

fun currentDateTimeText(instant: Instant = Instant.now(), pattern: String = "", zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val format = pattern.ifBlank { defaultCurrentDateTimePattern(locale) }
    require(format.length <= 200) { throw Problem("err_date") }
    return try { DateTimeFormatter.ofPattern(format, locale).format(instant.atZone(zone)) } catch (_: IllegalArgumentException) { throw Problem("err_date") }
}

internal fun parseCurrentDateTime(source: String, zone: ZoneId, locale: Locale = Locale.getDefault()): Long =
    LocalDateTime.parse(source, DateTimeFormatter.ofPattern(defaultCurrentDateTimePattern(locale).replace("yyyy", "uuuu"), locale).withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli()
