package com.germanium.shortcuts.core

fun dictionaryValue(type: String, source: String, context: RunContext): Value = when (type) {
    "text" -> Value.Text(context.render(source))
    "number" -> Value.Number(parseNumber(context.render(source)))
    "boolean" -> Value.BooleanValue(when (context.render(source)) { "true" -> true; "false" -> false; else -> throw Problem("err_invalid_data") })
    "auto" -> context.resolve(source)
    else -> throw Problem("err_invalid_data")
}
fun parseDictionary(source: String, context: RunContext): Value.Dictionary {
    val entries = source.lines().filter(String::isNotBlank).map { line ->
        val parts = line.split('|', limit = 3)
        require(parts.size == 3 && parts[1].isNotBlank()) { throw Problem("err_dictionary") }
        parts[1].trim() to dictionaryValue(parts[0].trim(), parts[2], context)
    }
    require(entries.map { it.first }.distinct().size == entries.size) { throw Problem("err_dictionary") }
    return Value.Dictionary(entries.toMap())
}
fun dictionaryEntries(parameters: Map<String, String>): List<EntrySpec> = readEntrySpecs(parameters, "entry", "entries")
    .filterNot { it.key.isBlank() && it.value.isEmpty() }
fun dictionaryFromParameters(parameters: Map<String, String>, context: RunContext): Value.Dictionary {
    val entries = dictionaryEntries(parameters)
    require(entries.all { it.key.isNotBlank() } && entries.map { it.key }.distinct().size == entries.size) { throw Problem("err_dictionary") }
    return Value.Dictionary(entries.associate { it.key to dictionaryValue(it.type, it.value, context) })
}
