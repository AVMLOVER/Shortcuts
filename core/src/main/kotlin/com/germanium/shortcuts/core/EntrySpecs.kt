package com.germanium.shortcuts.core

/** Editable typed entries live in ordinary XML action parameters, including multiline values. */
data class EntrySpec(val type: String, val key: String, val value: String)
fun readEntrySpecs(parameters: Map<String, String>, prefix: String, legacyKey: String): List<EntrySpec> {
    val pattern = Regex("${Regex.escape(prefix)}\\.([0-9]{1,3})\\.(key|type|value)")
    val keys = parameters.keys.filter { it.startsWith("$prefix.") }
    if (keys.isEmpty()) return parameters[legacyKey].orEmpty().lines().filter(String::isNotBlank).map {
        val parts = it.split('|', limit = 3)
        require(parts.size == 3) { throw Problem("err_invalid_data") }
        EntrySpec(parts[0].trim(), parts[1].trim(), parts[2])
    }
    require(parameters[legacyKey].isNullOrEmpty()) { throw Problem("err_invalid_data") }
    val indices = keys.map { key -> (pattern.matchEntire(key) ?: throw Problem("err_invalid_data")).groupValues[1].toInt() }.distinct().sorted()
    require(indices.size <= 256 && indices.all { it < 256 }) { throw Problem("err_size") }
    return indices.map { index -> EntrySpec(parameters["$prefix.$index.type"] ?: "text", parameters["$prefix.$index.key"].orEmpty(), parameters["$prefix.$index.value"].orEmpty()) }
}
fun writeEntrySpecs(parameters: Map<String, String>, prefix: String, legacyKey: String, entries: List<EntrySpec>): Map<String, String> =
    parameters.filterKeys { it != legacyKey && !it.startsWith("$prefix.") } + buildMap {
        entries.forEachIndexed { index, entry -> put("$prefix.$index.type", entry.type); put("$prefix.$index.key", entry.key); put("$prefix.$index.value", entry.value) }
    }
