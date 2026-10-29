package com.germanium.shortcuts.core

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object UrlValues {
    fun normalize(source: String, defaultScheme: String = "https"): String {
        val text = source.trim().replace(" ", "%20")
        require(text.isNotEmpty() && text.length <= 8192 && text.none { it.code < 32 }) { throw Problem("err_url") }
        val hostPort = Regex("(?i)^(?:localhost|[a-z0-9.-]+\\.[a-z0-9.-]+):[0-9]+(?:[/#?].*)?$").matches(text)
        val explicit = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(text) && !hostPort
        val normalized = if (text.startsWith("//")) "$defaultScheme:$text" else if (explicit) text else "$defaultScheme://$text"
        val uri = runCatching { URI(normalized) }.getOrElse { throw Problem("err_url") }
        require(!uri.scheme.isNullOrBlank()) { throw Problem("err_url") }
        if (uri.scheme.lowercase() in listOf("http", "https")) require(!uri.rawAuthority.isNullOrBlank() && !uri.rawAuthority.endsWith(':')) { throw Problem("err_url") }
        else require(!uri.rawSchemeSpecificPart.isNullOrBlank()) { throw Problem("err_url") }
        return normalized
    }
}

internal fun version104Actions(platform: PlatformActions): List<ActionHandler> = listOf(
    Handler("url") { p, c -> Value.Text(UrlValues.normalize(c.text(p, "url", "https://"))) },
    Handler("shutdown") { _, c -> require(c.mode == ExecutionMode.ROOT) { throw Problem("err_root_required") }; platform.power(false, c.mode); c.value },
    Handler("reboot") { p, c -> require(c.mode == ExecutionMode.ROOT) { throw Problem("err_root_required") }; val target=c.text(p,"target","system"); PowerTargets.command(true,target); platform.power(true, c.mode, target); c.value },
    Handler("write_partition") { p, c -> require(c.mode == ExecutionMode.ROOT) { throw Problem("err_root_required") }; val target=c.text(p,"partition"); PartitionWrites.target(target); platform.writePartition(c.text(p,"source","{{input}}"),target,c.mode); c.value },
    Handler("get_dictionary_from_input") { p, c ->
        val source = c.resolve(p["source"] ?: "{{input}}")
        val result = if (source is Value.Dictionary || source is Value.ListValue) source else JsonValues.parse(source.text())
        require(result is Value.Dictionary || result is Value.ListValue) { throw Problem("err_json") }; result
    },
    Handler("encode_json") { p, c -> Value.Text(HttpActions.json(c.resolve(p["source"] ?: "{{input}}"))) },
    Handler("adjust_date") { p, c ->
        val zone = zone(c.text(p, "zone")); val date = Instant.ofEpochMilli(parseEventTime(c.text(p, "source", "{{input}}"), zone)).atZone(zone)
        val amount = c.resolve(p["amount"] ?: "1").number().longValueExact()
        val adjusted = when (p["unit"] ?: "days") {
            "seconds" -> date.plusSeconds(amount); "minutes" -> date.plusMinutes(amount); "hours" -> date.plusHours(amount)
            "days" -> date.plusDays(amount); "weeks" -> date.plusWeeks(amount); "months" -> date.plusMonths(amount); "years" -> date.plusYears(amount)
            else -> throw Problem("err_date")
        }; Value.Text(adjusted.toOffsetDateTime().toString())
    },
    Handler("date_difference") { p, c ->
        val zone = zone(c.text(p, "zone")); val start = Instant.ofEpochMilli(parseEventTime(c.text(p, "start", "{{input}}"), zone)).atZone(zone)
        val endText = c.text(p, "end"); val end = (if (endText.isBlank()) Instant.now() else Instant.ofEpochMilli(parseEventTime(endText, zone))).atZone(zone)
        val unit = p["unit"] ?: "seconds"
        var result = when (unit) {
            "months" -> ChronoUnit.MONTHS.between(start, end).toBigDecimal(); "years" -> ChronoUnit.YEARS.between(start, end).toBigDecimal()
            else -> {
                val divisor = when (unit) { "milliseconds" -> 1; "seconds" -> 1000; "minutes" -> 60000; "hours" -> 3600000; "days" -> 86400000; "weeks" -> 604800000; else -> throw Problem("err_date") }
                Calculator().evaluate("(${end.toInstant().toEpochMilli()}-${start.toInstant().toEpochMilli()})/$divisor")
            }
        }
        if (p["absolute"] == "true") result = Calculator().evaluate("abs($result)")
        Value.Number(result)
    },
    Handler("format_filesize") { p, c ->
        var bytes = c.resolve(p["source"] ?: "{{input}}").number(); require(bytes.signum() >= 0) { throw Problem("err_number") }
        val binary = p["binary"] != "false"; val radix = if (binary) 1024.toBigDecimal() else 1000.toBigDecimal()
        val units = if (binary) listOf("B","KiB","MiB","GiB","TiB","PiB") else listOf("B","kB","MB","GB","TB","PB")
        var index = 0; while (bytes >= radix && index < units.lastIndex) { bytes = Calculator().evaluate("$bytes/$radix"); index++ }
        Value.Text(ContentNumbers.format(bytes, c.text(p,"digits","2").toIntOrNull() ?: throw Problem("err_number"), false, "") + " " + units[index])
    },
    Handler("rotate_image") { p, c -> platform.transformImage("rotate_image", c.resolve(p["source"] ?: "{{input}}"), p.mapValues { c.render(it.value) }) },
    Handler("convert_image") { p, c -> platform.transformImage("convert_image", c.resolve(p["source"] ?: "{{input}}"), p.mapValues { c.render(it.value) }) },
    Handler("make_archive") { p, c -> platform.archive(c.resolve(p["source"] ?: "{{input}}"), c.text(p,"folder","Archives"), c.text(p,"filename"), false) },
    Handler("extract_archive") { p, c -> platform.archive(c.resolve(p["source"] ?: "{{input}}"), c.text(p,"folder","Extracted"), "", true) },
    Handler("generate_qr") { p, c -> platform.generateQr(c.text(p,"source","{{input}}"), c.text(p,"size","512").toIntOrNull() ?: throw Problem("err_number"), p["correction"] ?: "M") },
    Handler("read_barcode") { p, c -> platform.readBarcode(c.text(p,"source","{{input}}")) },
    Handler("speak_text") { p, c -> platform.speak(c.text(p,"source","{{input}}"), c.text(p,"language"), c.text(p,"rate","1").toFloatOrNull() ?: throw Problem("err_number"), c.text(p,"pitch","1").toFloatOrNull() ?: throw Problem("err_number")); c.value },
    Handler("get_volume") { _, _ -> Value.Number(platform.volume().toBigDecimal()) },
    Handler("set_volume") { p, c -> val volume = c.resolve(p["percent"] ?: "50").number().intValueExact(); require(volume in 0..100) { throw Problem("err_number") }; platform.volume(volume); c.value },
    Handler("set_flashlight") { p, c -> platform.flashlight(when(c.text(p,"enabled","true")) { "true" -> true; "false" -> false; else -> throw Problem("err_invalid_data") }); c.value },
)

private fun zone(value: String): ZoneId = if (value.isBlank()) ZoneId.systemDefault() else runCatching { ZoneId.of(value) }.getOrElse { throw Problem("err_date") }

object PowerTargets {
    val all = listOf("system","recovery","bootloader","download","edl")
    fun command(reboot: Boolean, target: String = "system"): String {
        require(target in all) { throw Problem("err_invalid_data") }
        return if (!reboot) "reboot -p" else if (target == "system") "reboot" else "reboot $target"
    }
}

object PartitionWrites {
    fun imageName(name: String) { require(name.endsWith(".img",ignoreCase=true) && name.length in 5..255 && name.none { it.code < 32 || it == '/' || it == '\\' }) { throw Problem("err_partition_extension") } }
    fun target(path: String) { require(path.startsWith("/dev/block/") && path.length <= 400 && Regex("/dev/block/[A-Za-z0-9_./-]+").matches(path) && path.split('/').drop(1).none { it.isBlank() || it == "." || it == ".." }) { throw Problem("err_partition_target") } }
}
