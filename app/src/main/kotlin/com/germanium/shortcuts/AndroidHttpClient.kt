package com.germanium.shortcuts

import com.germanium.shortcuts.core.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference

internal class AndroidHttpClient {
    suspend fun request(spec: HttpRequestSpec): HttpResponseSpec = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = exchange(spec) { connection ->
                    active.set(connection)
                    if (!continuation.isActive) { connection?.disconnect(); throw CancellationException("Request cancelled") }
                }
                if (continuation.isActive) continuation.resume(response)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
        continuation.invokeOnCancellation {
            val connection = active.getAndSet(null)
            worker.cancel()
            if (connection != null) CoroutineScope(Dispatchers.IO).launch { connection.disconnect() }
        }
    }
    private fun exchange(spec: HttpRequestSpec, connected: (HttpURLConnection?) -> Unit): HttpResponseSpec {
        HttpActions.validateUrl(spec.url); HttpActions.validateHeaders(spec.headers)
        require(spec.method in HttpActions.methods + "HEAD") { throw Problem("err_http_method") }
        require((spec.body?.size ?: 0) <= 32 * 1024 * 1024) { throw Problem("err_size") }
        var next = URL(spec.url); var method = spec.method; var body = spec.body
        val headers = spec.headers.toMutableMap(); val visited = mutableSetOf<String>()
        repeat(8) {
            require(visited.add("$method $next")) { throw Problem("err_http_redirect") }
            HttpActions.validateUrl(next.toString())
            val connection = next.openConnection() as HttpURLConnection
            connected(connection)
            connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.requestMethod = method
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                body?.let { bytes -> connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size); connection.outputStream.use { it.write(bytes) } }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val destination = URL(next, connection.getHeaderField("Location") ?: throw Problem("err_http_redirect"))
                    HttpActions.validateUrl(destination.toString())
                    fun port(url: URL) = if (url.port < 0) url.defaultPort else url.port
                    if (next.host != destination.host || next.protocol != destination.protocol || port(next) != port(destination)) headers.keys.removeAll { it.lowercase() in listOf("authorization", "cookie", "proxy-authorization", "host") }
                    if (status == 303 && method != "HEAD" || status in listOf(301, 302) && method == "POST") {
                        method = "GET"; body = null; headers.keys.removeAll { it.equals("content-type", true) }
                    }
                    next = destination
                } else {
                    val responseHeaders = connection.headerFields.entries.filter { it.key != null }.groupBy { it.key.lowercase() }.mapValues { (_, entries) -> entries.flatMap { it.value } }
                    require(responseHeaders.size <= 256 && responseHeaders.entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 131072) { throw Problem("err_size") }
                    val bytes = if (!spec.readBody || method == "HEAD" || status !in 200..299 || status in listOf(204, 205)) byteArrayOf() else connection.inputStream.use { raw ->
                        val stream = if (connection.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw
                        stream.use { it.readBytesBounded(1048576) }
                    }
                    return HttpResponseSpec(next.toString(), status, responseHeaders, bytes)
                }
            } finally { connection.disconnect() }
        }
        throw Problem("err_http_redirect")
    }
}
