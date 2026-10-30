package com.germanium.shortcuts.core

import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

data class HttpRequestSpec(val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null, val readBody: Boolean = true)
data class HttpResponseSpec(val url: String, val status: Int, val headers: Map<String, List<String>> = emptyMap(), val body: ByteArray = byteArrayOf()) {
    fun text(): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull().orEmpty()
        val name = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        val charset = name?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return body.toString(charset)
    }
}

object HttpActions {
    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    fun validateUrl(url: String): URI {
        require(url.length <= 8192) { throw Problem("err_size") }
        val uri = runCatching { URI(url) }.getOrElse { throw Problem("err_url") }
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535) { throw Problem("err_url") }
        return uri
    }
    fun validateHeaders(headers: Map<String, String>) {
        require(headers.size <= 50 && headers.entries.sumOf { it.key.length + it.value.length } <= 65536) { throw Problem("err_size") }
        require(headers.entries.all { (key, value) -> key.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) && value.none { it == '\r' || it == '\n' || it.code < 32 && it != '\t' || it.code == 127 } && key.lowercase() !in listOf("content-length", "transfer-encoding", "connection", "upgrade") }) { throw Problem("err_http_header") }
    }
    private fun entries(p: Map<String, String>, prefix: String, c: RunContext) = readEntrySpecs(p, prefix, prefix).filterNot { it.key.isBlank() && it.value.isEmpty() }.map { it.copy(key = c.render(it.key)) }
    suspend fun request(p: Map<String, String>, c: RunContext, platform: PlatformActions, operation: String): Value {
        val url = urlWithQuery(c.text(p, "url", "{{input}}"), entries(p, "query", c).map { it.key to c.render(it.value) })
        validateUrl(url)
        val rows = entries(p, "header", c)
        require(rows.map { it.key.lowercase() }.distinct().size == rows.size) { throw Problem("err_http_header") }
        val headers = rows.associate { it.key to c.render(it.value) }.toMutableMap()
        validateHeaders(headers)
        if (operation in listOf("expand_url", "get_url_headers")) {
            var response = platform.requestHttp(HttpRequestSpec(url, "HEAD", headers, readBody = false))
            if (response.status in listOf(405, 501)) response = platform.requestHttp(HttpRequestSpec(url, "GET", headers, readBody = false))
            require(response.status in 200..299) { throw Problem("err_http_status") }
            return if (operation == "expand_url") Value.Text(response.url) else Value.Dictionary(response.headers.entries.filter { it.key.isNotBlank() }.associate { (key, values) ->
                key.lowercase() to if (values.size == 1) Value.Text(values.single()) else Value.ListValue(values.map(Value::Text))
            })
        }
        val configured = if (operation == "get_html") "GET" else p.getOrDefault("method", "GET")
        val method = when (configured) {
            "ask" -> platform.chooseMenu("HTTP", methods.map { it to it }) ?: throw CancellationException("Cancelled")
            "variable" -> c.text(p, "customMethod", "GET").trim().uppercase()
            else -> c.render(configured).trim().uppercase()
        }
        require(method in methods) { throw Problem("err_http_method") }
        var body: ByteArray? = null
        var contentType: String? = null
        if (method in listOf("POST", "PUT", "PATCH")) {
            val bodyRows = entries(p, "bodyEntry", c)
            require(bodyRows.all { it.key.isNotBlank() } && bodyRows.map { it.key }.distinct().size == bodyRows.size) { throw Problem("err_dictionary") }
            when (p.getOrDefault("bodyType", "json")) {
                "json" -> {
                    val raw = p["body"].orEmpty()
                    val value = if (raw.isBlank()) Value.Dictionary(bodyRows.associate { it.key to dictionaryValue(it.type, it.value, c) }) else c.resolve(raw)
                    body = (if (raw.isNotBlank() && value is Value.Text) value.value else json(value)).toByteArray(Charsets.UTF_8)
                    contentType = "application/json; charset=utf-8"
                }
                "form" -> { body = bodyRows.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(c.render(it.value), "UTF-8") }.toByteArray(Charsets.UTF_8); contentType = "application/x-www-form-urlencoded; charset=utf-8" }
                "file" -> { body = platform.readBinary(c.text(p, "bodyFile", "{{input}}")); contentType = c.text(p, "contentType").ifBlank { "application/octet-stream" } }
                "text" -> { body = c.text(p, "body").toByteArray(Charsets.UTF_8); contentType = c.text(p, "contentType").ifBlank { "text/plain; charset=utf-8" } }
                else -> throw Problem("err_invalid_data")
            }
            require(body.size <= 32 * 1024 * 1024) { throw Problem("err_size") }
        }
        if (contentType != null && headers.keys.none { it.equals("content-type", true) }) headers["Content-Type"] = contentType
        validateHeaders(headers)
        val response = platform.requestHttp(HttpRequestSpec(url, method, headers, body))
        require(response.status in 200..299) { throw Problem("err_http_status") }
        return Value.Text(response.text())
    }
    fun json(value: Value): String {
        fun quote(text: String) = "\"" + buildString { text.forEach { char -> append(when (char) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> if (char.code < 32) "\\u" + char.code.toString(16).padStart(4, '0') else char.toString() }) } } + "\""
        return when (value) {
            Value.Null -> "null"
            is Value.Text -> quote(value.value)
            is Value.RichText -> quote(value.plain)
            is Value.Number, is Value.BooleanValue -> value.text()
            is Value.ListValue -> value.items.joinToString(",", "[", "]", transform = ::json)
            is Value.Dictionary -> value.entries.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + json(it.value) }
        }
    }
    fun component(url: String, component: String): Value {
        require(url.length <= 8192) { throw Problem("err_size") }
        val uri = runCatching { URI(url) }.getOrElse { throw Problem("err_url") }
        require(!uri.scheme.isNullOrBlank()) { throw Problem("err_url") }
        val user = uri.userInfo.orEmpty().split(':', limit = 2)
        val parts = linkedMapOf<String, Value>("scheme" to Value.Text(uri.scheme), "user" to Value.Text(user.first()), "password" to Value.Text(user.getOrElse(1) { "" }), "host" to Value.Text(uri.host.orEmpty()), "port" to if (uri.port >= 0) Value.Number(uri.port.toBigDecimal()) else Value.Text(""), "path" to Value.Text(uri.path.orEmpty()), "query" to Value.Text(uri.rawQuery.orEmpty()), "fragment" to Value.Text(uri.fragment.orEmpty()))
        return if (component == "all") Value.Dictionary(parts) else parts[component] ?: throw Problem("err_invalid_data")
    }
}
