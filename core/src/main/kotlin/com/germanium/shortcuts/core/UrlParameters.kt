package com.germanium.shortcuts.core

fun urlWithQuery(url: String, parameters: List<Pair<String, String>>): String {
    if (parameters.isEmpty()) return url
    require(parameters.size <= 50 && parameters.all { it.first.isNotBlank() }) { throw Problem("err_invalid_data") }
    val fragment = url.substringAfter('#', "")
    val base = url.substringBefore('#')
    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    val result = base + (if ('?' in base) { if (base.endsWith('?') || base.endsWith('&')) "" else "&" } else "?") + parameters.joinToString("&") { encode(it.first) + "=" + encode(it.second) } + if ('#' in url) "#$fragment" else ""
    require(result.length <= 8192) { throw Problem("err_size") }; return result
}
