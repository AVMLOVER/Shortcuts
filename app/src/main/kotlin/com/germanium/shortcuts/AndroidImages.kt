package com.germanium.shortcuts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal class AndroidImages(private val app: ShortcutsApplication) {
    suspend fun bytes(source: String): ByteArray = withContext(Dispatchers.IO) {
        if (source.startsWith("content://")) app.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytesBounded(32 * 1024 * 1024) } ?: throw Problem("err_io")
        else app.workspace.readableFile(source).inputStream().use { it.readBytesBounded(32 * 1024 * 1024) }
    }
    suspend fun fileValue(uri: String, imageOnly: Boolean): Value {
        if (imageOnly) { describe(uri); return Value.Text(uri) }
        val data = bytes(uri)
        if (data.size <= XmlCodec.MAX_BYTES) {
            val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() }.getOrNull()
            if (text != null && text.none { it.code < 32 && it !in "\t\r\n" }) return ValueDocument.decode(text)
        }
        return Value.Text(uri)
    }
    private suspend fun describe(source: String): Pair<ByteArray, BitmapFactory.Options> {
        val data = bytes(source)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { throw Problem("err_image") }
        return data to options
    }
    suspend fun perform(operation: String, source: String, options: Map<String, String>): Value = withContext(Dispatchers.Default) {
        val (data, bounds) = describe(source)
        if (operation == "get_image") return@withContext Value.Text(source)
        val name = withContext(Dispatchers.IO) {
            if (source.startsWith("content://")) app.contentResolver.query(Uri.parse(source), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }.orEmpty()
            else source.substringAfterLast('/')
        }
        if (operation == "image_info") return@withContext Value.Dictionary(linkedMapOf("width" to Value.Number(bounds.outWidth.toBigDecimal()), "height" to Value.Number(bounds.outHeight.toBigDecimal()),
            "mimeType" to Value.Text(bounds.outMimeType.orEmpty()), "size" to Value.Number(data.size.toBigDecimal()), "name" to Value.Text(name), "path" to Value.Text(source)))
        require(operation == "crop_image" && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { throw Problem("err_image") }
        fun number(key: String) = options[key]?.toIntOrNull() ?: throw Problem("err_number")
        val x = number("x"); val y = number("y"); val width = number("width"); val height = number("height")
        require(x >= 0 && y >= 0 && width > 0 && height > 0 && x.toLong() + width <= bounds.outWidth && y.toLong() + height <= bounds.outHeight) { throw Problem("err_image") }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw Problem("err_image")
        try {
            val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
            try {
                val png = ByteArrayOutputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
                Value.Text(app.workspace.writeBinary(options["folder"] ?: "Images", options["filename"] ?: "crop-${UUID.randomUUID()}.png", png))
            } finally { if (cropped !== bitmap) cropped.recycle() }
        } finally { bitmap.recycle() }
    }
}
