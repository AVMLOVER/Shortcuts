package com.germanium.shortcuts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.BreakIterator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.zip.CRC32
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.*

/** A run keeps the original share payload even after preceding actions change its current value. */
object ContentInput {
    val kinds = setOf("any", "text", "images", "urls", "files")
    fun flatten(value: Value): List<Value> = when (value) { is Value.ListValue -> value.items.flatMap(::flatten); else -> listOf(value) }
    fun isPayload(value: Value) = value is Value.Dictionary && value.entries["_shared"] == Value.BooleanValue(true)
    fun numbers(text: String): Value.ListValue {
        require(text.length <= 262144) { throw Problem("err_size") }
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace('−', '-')
        val pattern = Regex("[+-]?(?:(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        val values = pattern.findAll(normalized).take(257).map { Value.Number(parseNumber(it.value.replace(",", ""))) }.toList()
        return Value.ListValue(values)
    }
    fun field(value: Value, key: String): Value? = (value as? Value.Dictionary)?.takeIf { isPayload(it) }?.entries?.get(key)
    fun urls(text: String): List<Value> = Regex("(?:[a-z][a-z0-9+.-]*://|mailto:|tel:)[^\\s<>\"\\u0000-\\u001f]+", RegexOption.IGNORE_CASE).findAll(text).map {
        var url = it.value.trimEnd('.', ',', ';', '!', '。', '，', '；')
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) while (url.endsWith(close) && url.count { c -> c == close } > url.count { c -> c == open }) url = url.dropLast(1)
        Value.Text(url)
    }.distinct().take(257).toList().also { require(it.size <= 256) { throw Problem("err_size") } }
    fun extract(kind: String, value: Value): Value {
        require(kind in kinds) { throw Problem("err_invalid_data") }
        if (kind == "any") return value
        field(value, kind)?.let { return it }
        return when (kind) {
            "text" -> Value.Text(if (value is Value.ListValue) value.items.joinToString("\n") { extract("text", it).text() } else value.text())
            "urls" -> Value.ListValue(flatten(value).flatMap { urls(it.text()) }.distinct())
            else -> Value.ListValue(emptyList())
        }
    }
    fun shareActions(shortcut: Shortcut): List<ActionStep> {
        fun find(steps: List<ActionStep>): List<ActionStep> = steps.filter { it.enabled }.flatMap { (if (it.type == "share_input") listOf(it) else emptyList()) + it.branches.flatMap { b -> find(b.steps) } }
        return find(shortcut.steps)
    }
    fun accepts(shortcut: Shortcut, payload: Value) = shareActions(shortcut).any { step ->
        val kind = step.parameters["contentType"] ?: "any"
        kind == "any" || extract(kind, payload).text().isNotEmpty()
    }
}

object ContentHash {
    val algorithms = listOf("MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA3-224", "SHA3-256", "SHA3-384", "SHA3-512", "BLAKE2b-512", "BLAKE2s-256", "RIPEMD-160", "SM3", "CRC32")
    fun digest(data: ByteArray, algorithm: String): String {
        if (algorithm == "CRC32") return CRC32().apply { update(data) }.value.toString(16).padStart(8, '0')
        val digest: Digest = when (algorithm) {
            "MD5" -> MD5Digest(); "SHA-1" -> SHA1Digest(); "SHA-224" -> SHA224Digest(); "SHA-256" -> SHA256Digest(); "SHA-384" -> SHA384Digest(); "SHA-512" -> SHA512Digest()
            "SHA3-224" -> SHA3Digest(224); "SHA3-256" -> SHA3Digest(256); "SHA3-384" -> SHA3Digest(384); "SHA3-512" -> SHA3Digest(512)
            "BLAKE2b-512" -> Blake2bDigest(512); "BLAKE2s-256" -> Blake2sDigest(256); "RIPEMD-160" -> RIPEMD160Digest(); "SM3" -> SM3Digest()
            else -> throw Problem("err_hash_algorithm")
        }
        digest.update(data, 0, data.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

object ContentNumbers {
    val statistics = listOf("sum", "average", "minimum", "maximum", "median", "mode", "range", "variance", "stddev", "count")
    @Volatile var nativeStatistics: ((String, List<String>) -> List<String>)? = null
    private val precision = MathContext(34, RoundingMode.HALF_EVEN)
    fun stats(source: Value, operation: String): Value {
        val items = if (source is Value.ListValue) source.items else source.text().trim().split(Regex("[,;\\s]+" )).filter(String::isNotBlank).map { Value.Text(it) }
        require(items.isNotEmpty() && items.size <= 256) { throw Problem("err_statistics") }
        val numbers = items.map { it.number() }
        require(operation in statistics) { throw Problem("err_statistics") }
        nativeStatistics?.let { native ->
            val result = native(operation, numbers.map { it.toPlainString() }).map { Value.Number(parseNumber(it)) }
            return if (operation == "mode") Value.ListValue(result) else result.singleOrNull() ?: throw Problem("err_statistics")
        }
        val sorted = numbers.sorted(); val count = numbers.size.toBigDecimal()
        val sum = numbers.fold(BigDecimal.ZERO) { a, b -> a.add(b, precision) }
        fun variance(): BigDecimal { val mean = sum.divide(count, precision); return numbers.fold(BigDecimal.ZERO) { a, b -> val d = b.subtract(mean, precision); a.add(d.multiply(d, precision), precision) }.divide(count, precision) }
        val result = when (operation) {
            "count" -> count; "sum" -> sum; "average" -> sum.divide(count, precision); "minimum" -> sorted.first(); "maximum" -> sorted.last()
            "median" -> if (sorted.size % 2 == 1) sorted[sorted.size / 2] else sorted[sorted.size / 2 - 1].add(sorted[sorted.size / 2], precision).divide(2.toBigDecimal(), precision)
            "mode" -> {
                val groups = numbers.groupBy { it.stripTrailingZeros() }; val most = groups.values.maxOf { it.size }
                return Value.ListValue(groups.filterValues { it.size == most }.keys.sorted().map { Value.Number(it) })
            }
            "range" -> sorted.last().subtract(sorted.first(), precision); "variance" -> variance(); "stddev" -> Calculator().evaluate("sqrt(${variance().toPlainString()})")
            else -> throw Problem("err_statistics")
        }
        return Value.Number(result)
    }
    fun format(number: BigDecimal, digits: Int, grouping: Boolean, locale: String): String {
        require(digits in 0..20 && locale.length <= 64) { throw Problem("err_number") }
        val selected = if (locale.isBlank()) Locale.getDefault() else Locale.forLanguageTag(locale).also { require(it.language.isNotBlank()) { throw Problem("err_invalid_data") } }
        return DecimalFormat(if (grouping) "#,##0" else "0", DecimalFormatSymbols.getInstance(selected)).apply {
            minimumFractionDigits = digits; maximumFractionDigits = digits; roundingMode = RoundingMode.HALF_UP; isGroupingUsed = grouping
        }.format(number)
    }
}

private fun imageOptions(operation: String, p: Map<String, String>, c: RunContext): Map<String, String> {
    val common = setOf("folder", "filename", "interactive")
    val keys = when (operation) {
        "mask_image" -> setOf("maskShape", "invert") + when (c.text(p, "maskShape", "ellipse")) { "custom" -> setOf("secondary", "maskMode"); "rounded" -> setOf("radius"); else -> emptySet() }
        "overlay_image" -> setOf("secondary", "scale", "opacity", "rotation", "offsetX", "offsetY")
        "flip_image" -> setOf("flipDirection")
        "combine_images" -> setOf("direction", "spacing", "matchDimensions") + if (c.text(p, "direction", "horizontal") == "grid") setOf("columns") else emptySet()
        "resize_image" -> setOf("width", "height", "keepAspect")
        "compress_image" -> setOf("quality", "imageFormat")
        "text_image" -> setOf("width", "fontSize", "padding", "alignment", "foregroundColor", "backgroundColor", "transparentBackground")
        "make_pdf" -> setOf("pdfInput", "pageSize", "fontSize", "margin", "saveDirectoryUri")
        else -> emptySet()
    }
    return p.filterKeys { it in common || it in keys }.mapValues { c.render(it.value) }
}

internal fun contentActions(platform: PlatformActions): List<ActionHandler> = listOf(
    Handler("factor_number") { p, c -> IntegerFactors.calculate(c.resolve(p["source"] ?: "{{input}}"), p["factorType"] ?: "prime_factors", p["powers"] == "true") },
    Handler("share_input", { require(it.getOrDefault("contentType", "any") in ContentInput.kinds) { throw Problem("err_invalid_data") } }) { p, c -> platform.convertInput(p["contentType"] ?: "any", c.shortcutInput) },
    Handler("comment") { _, c -> c.value },
    Handler("count") { p, c ->
        val value = c.resolve(p["source"] ?: "{{input}}")
        val count = when (p["countType"] ?: "items") {
            "items" -> when (value) { is Value.ListValue -> value.items.size; is Value.Dictionary -> value.entries.size; else -> 1 }
            "characters" -> value.text().let { it.codePointCount(0, it.length) }
            "words", "sentences" -> {
                val text = value.text(); val words = p["countType"] == "words"
                val iterator = if (words) BreakIterator.getWordInstance(Locale.getDefault()) else BreakIterator.getSentenceInstance(Locale.getDefault())
                iterator.setText(text); var first = iterator.first(); var end = iterator.next(); var n = 0
                while (end != BreakIterator.DONE) { val part = text.substring(first, end); if (if (words) part.any { it.isLetterOrDigit() } else part.isNotBlank()) n++; first = end; end = iterator.next() }; n
            }
            "lines" -> if (value.text().isEmpty()) 0 else value.text().lineSequence().count()
            else -> throw Problem("err_invalid_data")
        }; Value.Number(count.toBigDecimal())
    },
    Handler("statistics") { p, c -> ContentNumbers.stats(c.resolve(p["source"] ?: "{{input}}"), p["statistic"] ?: "average") },
    Handler("format_number") { p, c -> Value.Text(ContentNumbers.format(c.resolve(p["source"] ?: "{{input}}").number(), c.text(p, "digits", "2").toIntOrNull() ?: throw Problem("err_number"), p["grouping"] == "true", c.text(p, "locale"))) },
    Handler("battery_level") { _, _ -> Value.Number(platform.batteryLevel().also { require(it in 0..100) { throw Problem("err_backend") } }.toBigDecimal()) },
    Handler("share") { p, c -> platform.share(c.resolve(p["source"] ?: "{{input}}")); c.value },
    Handler("wait_return") { _, c -> platform.waitForReturn(); c.value },
    Handler("get_text_from_input") { p, c -> platform.convertInput("text", c.resolve(p["source"] ?: "{{input}}")) },
    Handler("get_images_from_input") { p, c -> platform.convertInput("images", c.resolve(p["source"] ?: "{{input}}")) },
    Handler("get_urls_from_input") { p, c -> platform.convertInput("urls", c.resolve(p["source"] ?: "{{input}}")) },
    Handler("get_files_from_input") { p, c -> platform.convertInput("files", c.resolve(p["source"] ?: "{{input}}")) },
    Handler("get_numbers_from_input") { p, c -> ContentInput.numbers(platform.convertInput("text", c.resolve(p["source"] ?: "{{input}}")).text()) },
    Handler("mask_image") { p, c -> platform.transformImage("mask_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("mask_image", p, c)) },
    Handler("overlay_image") { p, c -> platform.transformImage("overlay_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("overlay_image", p, c)) },
    Handler("flip_image") { p, c -> platform.transformImage("flip_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("flip_image", p, c)) },
    Handler("combine_images") { p, c -> platform.transformImage("combine_images", c.resolve(p["source"] ?: "{{input}}"), imageOptions("combine_images", p, c)) },
    Handler("resize_image") { p, c -> platform.transformImage("resize_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("resize_image", p, c)) },
    Handler("compress_image") { p, c -> platform.transformImage("compress_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("compress_image", p, c)) },
    Handler("text_image") { p, c -> platform.transformImage("text_image", c.resolve(p["source"] ?: "{{input}}"), imageOptions("text_image", p, c)) },
    Handler("make_pdf") { p, c -> platform.transformImage("make_pdf", c.resolve(p["source"] ?: "{{input}}"), imageOptions("make_pdf", p, c)) },
)
