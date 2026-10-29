package com.germanium.shortcuts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep the typed input so lists and shared image payloads do not turn into path text. */
@Composable internal fun QuickLookContent(app: ShortcutsApplication, source: Value, modifier: Modifier = Modifier) {
    val entries = remember(source) {
        if (ContentInput.isPayload(source)) {
            val text = ContentInput.field(source,"text")?.takeIf { it.text().isNotEmpty() }
            (listOfNotNull(text) + ContentInput.flatten(ContentInput.field(source,"images") ?: Value.ListValue(emptyList())) +
                ContentInput.flatten(ContentInput.field(source,"files") ?: Value.ListValue(emptyList()))).distinct()
        } else ContentInput.flatten(source)
    }
    LazyColumn(modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty()) item { Text(t("no_output")) }
        itemsIndexed(entries) { index, entry ->
            QuickLookItem(app, entry.text(), index)
        }
    }
}

private data class PreviewState(val loaded: Boolean = false, val bitmap: Bitmap? = null)

@Composable private fun QuickLookItem(app: ShortcutsApplication, text: String, index: Int) {
    val preview by produceState(PreviewState(), text) {
        value = PreviewState(true, loadPreview(app, text))
    }
    when {
        !preview.loaded -> Box(Modifier.fillMaxWidth().height(48.dp)) { CircularProgressIndicator(Modifier.size(28.dp)) }
        preview.bitmap != null -> Image(preview.bitmap!!.asImageBitmap(), t("image_preview"),
            Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("quick_look_image_$index"))
        else -> SelectionContainer {
            Column {
                Text(text.ifEmpty { t("no_output") }.take(65536))
                if (text.length > 65536) Text(t("preview_limited"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Decode by content, with bounded input and sampling, including files without an image extension. */
private suspend fun loadPreview(app: ShortcutsApplication, source: String): Bitmap? = withContext(Dispatchers.IO) {
    if (source.isEmpty() || source.length > 4096 || '\n' in source || '\r' in source) return@withContext null
    try {
        val data = AndroidImages(app).bytes(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1200) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return@withContext null
        val orientation = runCatching { java.io.ByteArrayInputStream(data).use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
        if (orientation !in 2..8) return@withContext decoded
        try {
            val matrix = Matrix().apply { when (orientation) {
                2 -> setScale(-1f,1f); 3 -> setRotate(180f); 4 -> setScale(1f,-1f)
                5 -> { setRotate(90f); postScale(-1f,1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f,1f) }; 8 -> setRotate(-90f)
            } }
            Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also { if (it !== decoded) decoded.recycle() }
        } catch (e: Exception) { decoded.recycle(); throw e }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
