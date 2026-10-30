package com.germanium.shortcuts

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Incoming grants are copied while the receiving Activity still owns the grant. */
internal class AndroidContent(private val app: ShortcutsApplication) {
    private val images = AndroidImages(app)
    private fun file(source: String) = app.workspace.readableFile(source)
    fun isFile(source: String): Boolean = source.startsWith("content://") || runCatching { file(source).isFile }.getOrDefault(false)
    fun mime(source: String): String = if (source.startsWith("content://")) app.contentResolver.getType(Uri.parse(source)).orEmpty().ifBlank { "application/octet-stream" }
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
    fun name(uri: Uri): String = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().ifBlank { "shared-file" }
    suspend fun receive(intent: Intent): Value = withContext(Dispatchers.IO) {
        require(intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) { throw Problem("err_shared_input") }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: intent.clipData?.let { clip -> require(clip.itemCount <= 16) { throw Problem("err_size") }; (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("\n") }.orEmpty()
        require(text.length <= 262144) { throw Problem("err_size") }
        @Suppress("DEPRECATION")
        val streams: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val clips = intent.clipData?.let { data -> require(data.itemCount <= 16) { throw Problem("err_size") }; (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
        val uris = (streams + clips).distinct(); require(uris.size <= 16 && uris.all { it.scheme == "content" }) { throw Problem("err_shared_input") }
        val files = mutableListOf<Value>(); val photos = mutableListOf<Value>(); val created = mutableListOf<String>(); var total = 0L
        try {
            for (uri in uris) {
                val data = app.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(32 * 1024 * 1024) } ?: throw Problem("err_io")
                total += data.size; require(total <= 64 * 1024 * 1024) { throw Problem("err_size") }
                var original = name(uri).take(96).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                if ('.' !in original) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime(uri.toString()))?.let { original += ".$it" }
                val path = app.workspace.importBinary("Shared", "${UUID.randomUUID()}-$original", data); created += path
                val value = Value.Text(path); files += value
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) photos += value
            }
            require(text.isNotEmpty() || files.isNotEmpty()) { throw Problem("err_shared_input") }
            Value.Dictionary(linkedMapOf("_shared" to Value.BooleanValue(true), "text" to Value.Text(text), "images" to Value.ListValue(photos), "files" to Value.ListValue(files), "urls" to Value.ListValue(ContentInput.urls(text))))
        } catch (e: Exception) { created.forEach { runCatching { file(it).delete() } }; throw e }
    }
    fun references(value: Value, kind: String = "files", limit: Int = 32): List<String> {
        val selected = ContentInput.field(value, kind) ?: value
        return ContentInput.flatten(selected).map { it.text() }.filter { isFile(it) }.also { require(it.size <= limit) { throw Problem("err_size") } }
    }
    suspend fun extract(kind: String, source: Value): Value = withContext(Dispatchers.IO) {
        if (kind == "any") return@withContext source
        if (kind == "text") {
            val values = if (ContentInput.isPayload(source)) {
                val text = ContentInput.field(source, "text")?.text().orEmpty()
                if (text.isNotEmpty()) listOf(Value.Text(text)) else ContentInput.flatten(ContentInput.field(source, "files") ?: Value.ListValue(emptyList()))
            } else ContentInput.flatten(source)
            val result = StringBuilder()
            for (value in values) {
                val text = if (isFile(value.text())) {
                    val bytes = images.bytes(value.text()); require(bytes.size <= 1048576) { throw Problem("err_size") }
                    try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().also { require('\u0000' !in it) { throw Problem("err_input_text") } } }
                    catch (e: java.nio.charset.CharacterCodingException) { throw Problem("err_input_text") }
                } else value.text()
                require(result.length.toLong() + text.length + 1 <= 262144) { throw Problem("err_size") }
                if (result.isNotEmpty()) result.append('\n'); result.append(text)
            }
            return@withContext Value.Text(result.toString())
        }
        if (kind == "urls") return@withContext ContentInput.field(source, "urls") ?: Value.ListValue(ContentInput.urls(extract("text", source).text()))
        val refs = references(source, kind)
        Value.ListValue(refs.filter { ref -> kind == "files" || runCatching {
            val bytes = images.bytes(ref); val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds); bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false) }.map { Value.Text(it) })
    }
    suspend fun shareIntent(value: Value): Intent = withContext(Dispatchers.IO) {
        if(value is Value.RichText) return@withContext Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_TEXT,value.plain).putExtra(Intent.EXTRA_HTML_TEXT,value.html),app.tr("action_share"))
        val refs = references(value)
        if (refs.isEmpty()) return@withContext Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, if (ContentInput.isPayload(value)) ContentInput.extract("text", value).text() else value.text()), app.tr("action_share"))
        require(refs.size <= 16) { throw Problem("err_size") }
        val directory = File(app.cacheDir, "shared").also { check(it.isDirectory || it.mkdirs()) }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.delete() }
        var total = 0L; val uris = arrayListOf<Uri>(); val types = mutableListOf<String>(); val created = mutableListOf<File>()
        try {
            for (ref in refs) {
                val bytes = images.bytes(ref); total += bytes.size; require(total <= 64 * 1024 * 1024) { throw Problem("err_size") }
                val type = mime(ref); types += type
                val original = if (ref.startsWith("content://")) name(Uri.parse(ref)) else ref.substringAfterLast('/')
                val target = File(directory, "${UUID.randomUUID()}-${original.take(96).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")}")
                created += target; target.writeBytes(bytes); uris += FileProvider.getUriForFile(app, "${app.packageName}.files", target)
            }
            val type = if (types.distinct().size == 1) types.first() else if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
            val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) send.putExtra(Intent.EXTRA_STREAM, uris.single()) else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.clipData = ClipData.newUri(app.contentResolver, "Shortcuts", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            val text = if (ContentInput.isPayload(value)) ContentInput.extract("text", value).text() else ContentInput.flatten(value).filterNot { isFile(it.text()) }.joinToString("\n") { it.text() }
            if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
            Intent.createChooser(send, app.tr("action_share")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { created.forEach { it.delete() }; throw e }
    }
}
