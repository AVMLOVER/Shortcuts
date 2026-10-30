package com.germanium.shortcuts.core

import java.math.BigDecimal

sealed interface Value {
    fun text(): String
    data object Null : Value { override fun text() = "" }
    data class Text(val value: String) : Value { override fun text() = value }
    data class RichText(val html: String, val plain: String) : Value {
        init { require(html.length + plain.length <= 262144) { throw Problem("err_size") } }
        override fun text() = plain
    }
    data class Number(val value: BigDecimal) : Value {
        init { require(value.precision() <= 1024 && value.scale() in -1024..1024) { throw Problem("err_number") } }
        override fun text(): String = value.stripTrailingZeros().toPlainString()
    }
    data class BooleanValue(val value: Boolean) : Value { override fun text() = value.toString() }
    data class ListValue(val items: List<Value>) : Value {
        init { require(items.size <= 256 && containerDepth(items) <= 16 && items.sumOf { it.storageLength().toLong() + 1 } <= 262144) { throw Problem("err_size") } }
        override fun text() = items.joinToString("\n") { it.text() }
    }
    data class Dictionary(val entries: Map<String, Value>) : Value {
        init {
            require(entries.size <= 256) { throw Problem("err_size") }; require(containerDepth(entries.values) <= 16) { throw Problem("err_size") }
            var length = 2L
            entries.forEach { (key, value) -> length += key.length + value.storageLength() + 4; require(length <= 262144) { throw Problem("err_size") } }
        }
        override fun text(): String = entries.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${it.value.text()}" }
    }
}
fun Value.storageLength(): Int = when(this) {
    is Value.RichText -> html.length+plain.length
    is Value.ListValue -> items.sumOf { it.storageLength()+1 }
    is Value.Dictionary -> entries.entries.sumOf { it.key.length+it.value.storageLength()+4 }+2
    else -> text().length
}
private fun containerDepth(values: Collection<Value>): Int = 1 + (values.maxOfOrNull { when (it) { is Value.Dictionary -> containerDepth(it.entries.values); is Value.ListValue -> containerDepth(it.items); else -> 0 } } ?: 0)
fun Value.number(): BigDecimal = when (this) {
    Value.Null -> throw Problem("err_number")
    is Value.Number -> value
    is Value.Text -> parseNumber(value)
    is Value.RichText -> parseNumber(plain)
    is Value.BooleanValue -> throw Problem("err_number")
    is Value.Dictionary -> throw Problem("err_dictionary")
    is Value.ListValue -> throw Problem("err_number_list")
}
fun parseNumber(source: String): BigDecimal {
    require(source.length <= 1024) { throw Problem("err_size") }
    return Value.Number(source.trim().toBigDecimalOrNull() ?: throw Problem("err_number")).value
}
fun validateVariableName(name: String) {
    require(name.matches(Regex("[\\p{L}_][\\p{L}\\p{N}_]{0,63}")) && name != "input") { throw Problem("err_variable_name") }
}
class VariableScope {
    private val values = linkedMapOf<String, Value>()
    private val lengths = mutableMapOf<String, Int>()
    private var totalLength = 0L
    private val outputs = mutableMapOf<String, Value>()
    private val namedOrigins = mutableMapOf<String, Set<InputOrigin>>()
    private val outputOrigins = mutableMapOf<String, Set<InputOrigin>>()
    fun origins(name: String): Set<InputOrigin> = namedOrigins[name].orEmpty()
    fun outputOrigins(id: String): Set<InputOrigin> = outputOrigins[id] ?: outputOrigins[id.substringBefore('.')].orEmpty()
    private val outputLengths = mutableMapOf<String, Int>()
    private var outputLength = 0L
    fun output(id: String): Value = outputs[id] ?: throw Problem("err_variable_missing")
    fun outputProperty(id: String, key: String): Value? = outputs["$id.$key"]
    fun record(id: String, value: Value, origins: Set<InputOrigin> = emptySet()) {
        outputOrigins[id] = origins
        val length = value.storageLength()
        require(outputLength - (outputLengths[id] ?: 0) + length <= 8388608) { throw Problem("err_size") }
        outputLength += length - (outputLengths[id] ?: 0); outputLengths[id] = length; outputs[id] = value
    }
    fun create(name: String, value: Value, origins: Set<InputOrigin> = emptySet()) { require(name !in values) { throw Problem("err_variable_exists") }; set(name, value, origins) }
    fun append(name: String, value: Value, origins: Set<InputOrigin> = emptySet()): Value {
        val previous = get(name)
        val result = Value.ListValue((if (previous is Value.ListValue) previous.items else listOf(previous)) + (if (value is Value.ListValue) value.items else listOf(value)))
        set(name, result, origins(name) + origins); return result
    }
    fun get(name: String): Value = values[name] ?: throw Problem("err_variable_missing")
    fun set(name: String, value: Value, origins: Set<InputOrigin> = emptySet()) {
        validateVariableName(name)
        require(values.containsKey(name) || values.size < 256) { throw Problem("err_size") }
        val length = value.storageLength()
        require(length <= 262144 && totalLength - (lengths[name] ?: 0) + length <= 4194304) { throw Problem("err_size") }
        totalLength += length - (lengths[name] ?: 0)
        lengths[name] = length
        values[name] = value; namedOrigins[name] = origins
    }
    fun snapshot(): Map<String, Value> = values.toMap()
    fun remove(name: String) { namedOrigins.remove(name); values.remove(name); totalLength -= lengths.remove(name) ?: 0 }
}
data class RunContext(val mode: ExecutionMode, val value: Value, val variables: VariableScope, val angleUnit: AngleUnit = AngleUnit.DEGREES, val shortcutInput: Value = value, val inputOrigin: InputOrigin = InputOrigin.NONE, val valueOrigins: Set<InputOrigin> = emptySet()) {
    fun referenceOrigins(name: String): Set<InputOrigin> = when { name == "input" -> valueOrigins; name.startsWith("@") -> variables.outputOrigins(name.drop(1)); else -> variables.origins(name) }
    fun originsOf(template: String) = TOKEN.findAll(template).flatMap { referenceOrigins(it.groupValues[1].trim()) }.toSet()
    val input: String get() = value.text()
    fun reference(name: String): Value = when {
        name == "input" -> value
        name.startsWith("@") -> {
            val path = name.drop(1).split('.')
            var current = variables.output(path.first())
            path.drop(1).forEachIndexed { index, key -> current = (if (index == 0) variables.outputProperty(path.first(), key) else null) ?: (current as? Value.Dictionary)?.entries?.get(key) ?: throw Problem("err_variable_missing") }
            current
        }
        else -> variables.get(name)
    }
    fun render(template: String): String = substitute(template) { reference(it).text() }
    fun resolve(template: String): Value = TOKEN.matchEntire(template)?.let { reference(it.groupValues[1].trim()) } ?: Value.Text(render(template))
    fun text(parameters: Map<String, String>, key: String, default: String = "") = render(parameters[key] ?: default)
    fun expression(source: String): String = substitute(source) { reference(it).number().toPlainString() }
    private fun substitute(source: String, lookup: (String) -> String): String {
        require(source.length <= 262144) { throw Problem("err_size") }
        val result = StringBuilder()
        var cursor = 0
        for (match in TOKEN.findAll(source)) {
            val replacement = lookup(match.groupValues[1].trim())
            require(result.length.toLong() + match.range.first - cursor + replacement.length <= 262144) { throw Problem("err_size") }
            result.append(source, cursor, match.range.first).append(replacement)
            cursor = match.range.last + 1
        }
        require(result.length.toLong() + source.length - cursor <= 262144) { throw Problem("err_size") }
        return result.append(source, cursor, source.length).toString()
    }
    companion object { private val TOKEN = Regex("\\{\\{([^{}]+)\\}\\}") }
}
