package com.germanium.shortcuts.core

/** Strict, bounded JSON with typed null, exact decimals and duplicate-key rejection. */
object JsonValues {
    fun parse(source: String): Value {
        require(source.length <= 262144) { throw Problem("err_size") }
        return Reader(source).run { val result = value(0); whitespace(); check(index == text.length); result }
    }
    private class Reader(val text: String) {
        var index = 0
        fun check(ok: Boolean) { if (!ok) throw Problem("err_json") }
        fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        fun take(c: Char): Boolean { whitespace(); if (text.getOrNull(index) != c) return false; index++; return true }
        fun string(): String {
            check(take('"')); val result = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                if (c == '"') {
                    val out = result.toString(); check(out.codePoints().allMatch { it !in 0xD800..0xDFFF }); return out
                }
                check(c.code >= 32)
                if (c != '\\') { result.append(c); continue }
                check(index < text.length)
                result.append(when (val escaped = text[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { check(index + 4 <= text.length); val code = text.substring(index, index + 4).toIntOrNull(16) ?: throw Problem("err_json"); index += 4; code.toChar() }
                    else -> throw Problem("err_json")
                })
            }
            throw Problem("err_json")
        }
        fun value(depth: Int): Value {
            check(depth <= 16); whitespace()
            return when (text.getOrNull(index)) {
                '{' -> {
                    index++; val entries = linkedMapOf<String, Value>()
                    if (!take('}')) do {
                        check(entries.size < 256); val key = string(); check(key !in entries && take(':')); entries[key] = value(depth + 1)
                        if (take('}')) break
                        check(take(','))
                    } while (true)
                    Value.Dictionary(entries)
                }
                '[' -> {
                    index++; val items = mutableListOf<Value>()
                    if (!take(']')) do {
                        check(items.size < 256); items += value(depth + 1)
                        if (take(']')) break
                        check(take(','))
                    } while (true)
                    Value.ListValue(items)
                }
                '"' -> Value.Text(string())
                't' -> { literal("true"); Value.BooleanValue(true) }
                'f' -> { literal("false"); Value.BooleanValue(false) }
                'n' -> { literal("null"); Value.Null }
                else -> {
                    val match = NUMBER.find(text, index); check(match != null && match.range.first == index)
                    index = match!!.range.last + 1; Value.Number(parseNumber(match.value))
                }
            }
        }
        fun literal(value: String) { check(text.startsWith(value, index)); index += value.length }
    }
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
