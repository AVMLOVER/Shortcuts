package com.germanium.shortcuts.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

internal fun asList(value: Value): List<Value> = when (value) { is Value.ListValue -> value.items; is Value.Dictionary -> value.entries.values.toList(); else -> throw Problem("err_list") }
internal fun libraryActions(platform: PlatformActions): List<ActionHandler> = listOf(
    Handler("round_number") { p, c ->
        val number = c.resolve(p["source"] ?: "{{input}}").number()
        val digits = c.resolve(p["digits"] ?: "0").number().intValueExact()
        val function = when (p.getOrDefault("rounding", "nearest")) { "nearest" -> "round"; "up" -> "ceil"; "down" -> "floor"; else -> throw Problem("err_math") }
        Value.Number(Calculator(c.angleUnit).evaluate("$function($number,$digits)"))
    },
    Handler("current_location") { _, _ -> platform.currentLocation() },
    Handler("choose_file") { _, _ -> platform.chooseFile(false) },
    Handler("get_image") { p, c -> val source = c.text(p, "source"); if (source.isBlank()) platform.chooseFile(true) else platform.image("get_image", source, emptyMap()) },
    Handler("take_photo") { _, _ -> platform.takePhoto() },
    Handler("crop_image") { p, c -> platform.image("crop_image", c.text(p, "source", "{{input}}"), p.mapValues { c.render(it.value) }) },
    Handler("image_info") { p, c -> platform.image("image_info", c.text(p, "source", "{{input}}"), emptyMap()) },
    Handler("show_result") { p, c ->
        if (!platform.showResult(c.text(p, "title", "Shortcuts"), c.resolve(p["body"] ?: "{{input}}"), p["allowCancel"] == "true")) throw kotlinx.coroutines.CancellationException("Result cancelled")
        c.value
    },
    Handler("view_html") { p, c -> platform.viewHtml(c.text(p, "html", "{{input}}")); c.value },
    Handler("vibrate") { p, c ->
        val duration = durationMillis(c.text(p, "duration", "200"), p["unit"] ?: "milliseconds")
        val amplitude = c.text(p, "amplitude", "128").toIntOrNull() ?: throw Problem("err_number")
        require(amplitude in 1..255) { throw Problem("err_number") }; platform.vibrate(duration, amplitude); c.value
    },
    Handler("shortcut_count") { _, _ -> Value.Number(platform.shortcutCount().toBigDecimal()) },
    Handler("number") { p, c -> Value.Number(c.resolve(p["value"] ?: "0").number()) },
    Handler("read_calendar") { p, c ->
        val zone = c.text(p, "zone").let { if (it.isBlank()) ZoneId.systemDefault() else try { ZoneId.of(it) } catch (_: Exception) { throw Problem("err_date") } }
        val source = c.text(p, "date", "{{input}}")
        val date = try { LocalDate.parse(source) } catch (_: Exception) { Instant.ofEpochMilli(parseEventTime(source, zone)).atZone(zone).toLocalDate() }
        platform.readCalendarDay(CalendarDaySpec(date, zone))
    },
    Handler("screenshot") { p, c -> platform.screenshot(c.text(p, "folder", "Screenshots"), c.text(p, "filename", "screenshot.png")) },
    Handler("create_variable", { validateVariableName(it["name"].orEmpty()) }) { p, c -> val value = dictionaryValue(p["valueType"] ?: "auto", p["value"] ?: "{{input}}", c); c.variables.create(p.getValue("name"), value, c.originsOf(p["value"] ?: "{{input}}")); value },
    Handler("append_variable", { validateVariableName(it["name"].orEmpty()) }) { p, c -> c.variables.append(p.getValue("name"), c.resolve(p["value"] ?: "{{input}}"), c.originsOf(p["value"] ?: "{{input}}")) },
    Handler("ask_input") { p, c ->
        val type = p["inputType"] ?: "text"
        val answer = platform.askInput(c.text(p, "title", "Shortcuts"), c.text(p, "prompt"), c.text(p, "initial"), type) ?: throw kotlinx.coroutines.CancellationException("Input cancelled")
        when (type) { "number" -> Value.Number(parseNumber(answer)); "date" -> { parseEventTime(answer); Value.Text(answer) }; else -> Value.Text(answer) }
    },
    Handler("list") { p, c ->
        val items = if (p.keys.any { it.startsWith("item.") }) readEntrySpecs(p, "item", "items").map { dictionaryValue(it.type, it.value, c) }
            else p["items"].orEmpty().lines().filter { it.isNotEmpty() }.map(c::resolve)
        Value.ListValue(items)
    },
    Handler("get_list_item") { p, c ->
        val items = asList(c.resolve(p["source"] ?: "{{input}}")); require(items.isNotEmpty()) { throw Problem("err_list") }
        fun index(key: String): Int = (c.text(p, key, "1").toIntOrNull() ?: throw Problem("err_list")) - 1
        when (p.getOrDefault("selection", "index")) {
            "first" -> items.first(); "last" -> items.last(); "random" -> items[ThreadLocalRandom.current().nextInt(items.size)]
            "range" -> { val first = index("index"); val last = index("endIndex"); require(first in items.indices && last in first..items.lastIndex) { throw Problem("err_list") }; Value.ListValue(items.subList(first, last + 1)) }
            else -> items.getOrNull(index("index")) ?: throw Problem("err_list")
        }
    },
    Handler("choose_from_list") { p, c ->
        val source = c.resolve(p["source"] ?: "{{input}}")
        val items = asList(source); require(items.isNotEmpty()) { throw Problem("err_list") }
        val labels = if (source is Value.Dictionary) source.entries.keys.toList() else items.map { it.text().take(256) }
        val options = labels.mapIndexed { index, label -> index.toString() to label }
        val selected = if (p["multiple"] == "true") platform.chooseMany(c.text(p, "title", "Shortcuts"), options)
            else platform.chooseMenu(c.text(p, "title", "Shortcuts"), options)?.let { listOf(it) }
        if (selected == null) throw kotlinx.coroutines.CancellationException("List selection cancelled")
        val values = selected.map { items.getOrNull(it.toIntOrNull() ?: -1) ?: throw Problem("err_list") }
        if (p["multiple"] == "true") Value.ListValue(values) else values.single()
    },
    Handler("count_items") { p, c -> Value.Number(asList(c.resolve(p["source"] ?: "{{input}}")).size.toBigDecimal()) },
    Handler("sort_list") { p, c ->
        val items = asList(c.resolve(p["source"] ?: "{{input}}"))
        var result = if (p["numeric"] == "true") items.sortedBy { it.number() } else items.sortedBy { it.text().lowercase(Locale.ROOT) }
        if (p["descending"] == "true") result = result.asReversed()
        Value.ListValue(result)
    },
    Handler("reverse_list") { p, c -> Value.ListValue(asList(c.resolve(p["source"] ?: "{{input}}")).asReversed()) },
    Handler("dictionary_keys") { p, c -> val value = c.resolve(p["source"] ?: "{{input}}") as? Value.Dictionary ?: throw Problem("err_dictionary"); Value.ListValue(value.entries.keys.map { Value.Text(it) }) },
    Handler("calculate_expression", { require(it["expression"].orEmpty().isNotBlank()) { throw Problem("err_math") } }) { p, c -> Value.Number(Calculator(c.angleUnit) { c.reference(it).number() }.evaluate(c.render(p.getValue("expression")))) },
    Handler("open_app", { require(it["packageName"].orEmpty().isNotBlank()) { throw Problem("err_no_handler") } }) { p, c -> platform.openApp(p.getValue("packageName"), p["className"].orEmpty()); c.value },
    Handler("reminder") { p, c -> platform.postNotification("Shortcuts", c.resolve(p["body"] ?: "{{input}}")); c.value },
    Handler("current_date") { p, c ->
        val zone = c.text(p, "zone").let { if (it.isBlank()) ZoneId.systemDefault() else try { ZoneId.of(it) } catch (_: Exception) { throw Problem("err_date") } }
        Value.Text(currentDateTimeText(pattern = c.text(p, "format"), zone = zone))
    },
    Handler("format_date") { p, c ->
        val zone = p["zone"].orEmpty().let { if (it.isBlank()) ZoneId.systemDefault() else ZoneId.of(c.render(it)) }
        val epoch = parseEventTime(c.text(p, "source", "{{input}}"), zone)
        Value.Text(DateTimeFormatter.ofPattern(c.text(p, "format", "yyyy-MM-dd HH:mm:ss"), Locale.getDefault()).format(Instant.ofEpochMilli(epoch).atZone(zone)))
    },
    Handler("random_number") { p, c -> val min = c.text(p, "minimum", "1").toLongOrNull() ?: throw Problem("err_number"); val max = c.text(p, "maximum", "100").toLongOrNull() ?: throw Problem("err_number"); require(min in -1000000000..1000000000 && max in min..1000000000) { throw Problem("err_number") }; Value.Number(ThreadLocalRandom.current().nextLong(min, max + 1).toBigDecimal()) },
    Handler("generate_uuid") { p, c -> val seed = c.text(p, "seed", "{{input}}"); Value.Text(if (seed.isBlank()) java.util.UUID.randomUUID().toString() else java.util.UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()) },
    Handler("encode_base64") { p, c ->
        val source = c.resolve(p["source"] ?: "{{input}}").text()
        val bytes = if (p["inputType"] == "image" || p["inputType"] == "file") platform.readBinary(source) else source.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 3 * 1024 * 1024) { throw Problem("err_size") }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val wrap = (p["lineLength"] ?: "0").toIntOrNull() ?: throw Problem("err_number")
        require(wrap == 0 || wrap in 4..256) { throw Problem("err_number") }
        val output = if (wrap == 0) encoded else encoded.chunked(wrap).joinToString("\n")
        require(output.length <= 4 * 1024 * 1024) { throw Problem("err_size") }; Value.Text(output)
    },
    Handler("decode_base64") { p, c -> val bytes = Base64.getDecoder().decode(c.resolve(p["source"] ?: "{{input}}").text().filterNot { it in " \t\r\n" }); Value.Text(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()) },
    Handler("hash_text") { p, c ->
        val source = c.text(p, "source", "{{input}}")
        val bytes = if (p["inputType"] in listOf("file", "image")) platform.readBinary(source) else source.toByteArray(Charsets.UTF_8)
        Value.Text(ContentHash.digest(bytes, p.getOrDefault("algorithm", "SHA-256")))
    },
    Handler("encode_url") { p, c -> Value.Text(java.net.URLEncoder.encode(c.text(p, "source", "{{input}}"), "UTF-8")) },
    Handler("decode_url") { p, c -> Value.Text(java.net.URLDecoder.decode(c.text(p, "source", "{{input}}"), "UTF-8")) },
    Handler("get_url") { p, c -> HttpActions.request(p, c, platform, "get_url") },
    Handler("get_html") { p, c -> HttpActions.request(p, c, platform, "get_html") },
    Handler("expand_url") { p, c -> HttpActions.request(p, c, platform, "expand_url") },
    Handler("get_url_headers") { p, c -> HttpActions.request(p, c, platform, "get_url_headers") },
    Handler("get_url_component") { p, c -> HttpActions.component(c.text(p, "url", "{{input}}"), p.getOrDefault("component", "host")) },
    Handler("open_url") { p, c -> platform.openUrl(UrlValues.normalize(c.text(p, "url", "{{input}}"), "http")); c.value },
    Handler("get_application_list") { p, c ->
        val offset = c.text(p, "offset", "0").toIntOrNull() ?: throw Problem("err_number")
        val limit = c.text(p, "limit", "256").toIntOrNull() ?: throw Problem("err_number")
        require(offset >= 0 && limit in 1..256 && p.getOrDefault("format", "package") in listOf("package", "label", "both")) { throw Problem("err_invalid_data") }
        platform.applicationList(p["includeSystem"] == "true", p.getOrDefault("format", "package"), offset, limit)
    },
    Handler("get_clipboard") { _, _ -> Value.Text(platform.clipboard()) },
    Handler("set_clipboard") { p, c -> Value.Text(platform.clipboard(c.text(p, "value", "{{input}}"))) },
    Handler("device_info") { _, _ -> platform.deviceInfo() },
) + listOf("create_folder", "create_file", "read_file", "write_file", "append_file", "list_files", "delete_file", "file_info", "file_metadata").map { operation ->
    Handler(operation) { p, c -> platform.fileAction(operation, c.text(p, "folder", "Documents"), c.text(p, "filename"), c.resolve(p["content"] ?: "{{input}}"), c.mode, p["external"] == "true") }
}
