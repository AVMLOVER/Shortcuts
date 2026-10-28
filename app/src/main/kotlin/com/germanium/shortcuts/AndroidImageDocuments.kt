package com.germanium.shortcuts

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.material3.*
import androidx.compose.ui.graphics.toArgb
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID

internal class AndroidImageDocuments(private val app: ShortcutsApplication) {
    private val content = AndroidContent(app)
    private fun integer(options: Map<String, String>, key: String, default: Int, range: IntRange) = (options[key]?.toIntOrNull() ?: if (options[key].isNullOrBlank()) default else throw Problem("err_number")).also { require(it in range) { throw Problem("err_number") } }
    private fun layout(text: String, width: Int, font: Int, color: Int, alignment: String): StaticLayout {
        require(text.length <= 262144 && width > 0) { throw Problem("err_size") }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = font.toFloat(); this.color = color }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(when (alignment) { "center" -> Layout.Alignment.ALIGN_CENTER; "end" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }).setIncludePad(true).build()
    }
    private suspend fun palette(): Pair<Int, Int> {
        val dark = when (app.settings.load().theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
        val colors = if (android.os.Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app) } else if (dark) darkColorScheme() else lightColorScheme()
        return colors.onSurface.toArgb() to colors.surface.toArgb()
    }
    suspend fun perform(operation: String, source: Value, options: Map<String, String>): Value = withContext(Dispatchers.Default) {
        if (operation == "text_image") {
            val text = content.extract("text", source).text()
            val width = integer(options, "width", 1024, 32..8192); val padding = integer(options, "padding", 24, 0..1024)
            require(width > padding * 2) { throw Problem("err_image") }
            val font = integer(options, "fontSize", 32, 4..512); val colors = palette()
            fun color(key: String, fallback: Int) = options[key]?.takeIf(String::isNotBlank)?.let { runCatching { Color.parseColor(it) }.getOrElse { throw Problem("err_image") } } ?: fallback
            val layout = layout(text, width - padding * 2, font, color("foregroundColor", colors.first), options["alignment"] ?: "start")
            val height = layout.height + padding * 2
            require(height in 1..16384 && width.toLong() * height <= minOf(16_000_000L, Runtime.getRuntime().maxMemory() / 12)) { throw Problem("err_image") }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply { if (options["transparentBackground"] != "true") drawColor(color("backgroundColor", colors.second)); translate(padding.toFloat(), padding.toFloat()); layout.draw(this) }
                val bytes = ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
                val name = options["filename"]?.takeIf(String::isNotBlank) ?: "text-${UUID.randomUUID()}.png"
                require(name.endsWith(".png", true)) { throw Problem("err_path") }
                return@withContext Value.Text(app.workspace.writeBinary(options["folder"] ?: "Images", name, bytes))
            } finally { bitmap.recycle() }
        }
        require(operation == "make_pdf") { throw Problem("err_invalid_data") }
        val document = PdfDocument(); var pageNumber = 0
        val letter = options["pageSize"] == "letter"; val width = if (letter) 612 else 595; val height = if (letter) 792 else 842
        val margin = integer(options, "margin", 36, 0..200); val font = integer(options, "fontSize", 12, 4..96)
        val bodyHeight = height - margin * 2; val bodyWidth = width - margin * 2
        fun page(): PdfDocument.Page { require(++pageNumber <= 100) { throw Problem("err_pdf") }; return document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create()) }
        fun textPages(text: String) {
            val textLayout = layout(text, bodyWidth, font, Color.BLACK, "start")
            var firstLine = 0
            do {
                val top = textLayout.getLineTop(firstLine)
                var lastLine = textLayout.getLineForVertical(top + bodyHeight)
                while (lastLine > firstLine && textLayout.getLineBottom(lastLine) > top + bodyHeight) lastLine--
                require(textLayout.getLineBottom(firstLine) - top <= bodyHeight) { throw Problem("err_pdf") }
                val current = page()
                try { current.canvas.apply { drawColor(Color.WHITE); clipRect(margin, margin, width - margin, height - margin); translate(margin.toFloat(), (margin - top).toFloat()); textLayout.draw(this) } }
                finally { document.finishPage(current) }
                firstLine = lastLine + 1
            } while (firstLine < textLayout.lineCount)
        }
        try {
            val parts = when (options["pdfInput"] ?: "auto") {
                "text" -> listOf(content.extract("text", source))
                "images" -> ContentInput.flatten(content.extract("images", source))
                "auto" -> if (ContentInput.isPayload(source)) listOfNotNull(ContentInput.field(source, "text")?.takeIf { it.text().isNotBlank() }) + ContentInput.flatten(ContentInput.field(source, "files") ?: Value.ListValue(emptyList())) else ContentInput.flatten(source)
                else -> throw Problem("err_pdf")
            }
            require(parts.isNotEmpty() && parts.size <= 32) { throw Problem("err_pdf") }
            for (part in parts) {
                coroutineContext.ensureActive()
                val ref = part.text()
                if (content.isFile(ref)) {
                    val photos = content.extract("images", part) as Value.ListValue
                    if (photos.items.isNotEmpty()) {
                        val bitmap = AndroidImageTransforms(app).render("resize_image", part, mapOf("width" to "1190", "height" to "1684", "keepAspect" to "true"), preview = true)
                        try {
                            val current = page()
                            try { current.canvas.apply { drawColor(Color.WHITE); val scale = minOf(bodyWidth.toFloat() / bitmap.width, bodyHeight.toFloat() / bitmap.height); val w = bitmap.width * scale; val h = bitmap.height * scale; drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG)) } }
                            finally { document.finishPage(current) }
                        } finally { bitmap.recycle() }
                    } else textPages(content.extract("text", part).text())
                } else textPages(part.text())
            }
            val bytes = ByteArrayOutputStream().use { buffer ->
                val bounded = object : OutputStream() {
                    private var count = 0L
                    override fun write(value: Int) { require(++count <= 32 * 1024 * 1024) { throw Problem("err_size") }; buffer.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length; require(count <= 32 * 1024 * 1024) { throw Problem("err_size") }; buffer.write(bytes, offset, length) }
                }
                document.writeTo(bounded); buffer.toByteArray()
            }
            val name = options["filename"]?.takeIf(String::isNotBlank) ?: "document-${UUID.randomUUID()}.pdf"
            require(name.endsWith(".pdf", true)) { throw Problem("err_path") }
            savePdf(app, options["saveDirectoryUri"].orEmpty(), name, bytes)
        } finally { document.close() }
    }
}
