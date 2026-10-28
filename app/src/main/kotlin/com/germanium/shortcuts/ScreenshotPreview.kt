package com.germanium.shortcuts

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ScreenshotPreview(app: ShortcutsApplication, path: String) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        val uri = path.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        if (path.length > 4096 || (uri == null && path.substringAfterLast('.').lowercase() !in setOf("png", "jpg", "jpeg", "webp"))) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = if (uri == null) app.workspace.imageFile(path) ?: return@runCatching null else null
                if (uri != null && app.contentResolver.getType(uri)?.startsWith("image/") != true) return@runCatching null
                fun open() = if (uri != null) app.contentResolver.openInputStream(uri) else file?.inputStream()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: return@runCatching null
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
                open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, t("action_screenshot"), Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("screenshot_preview")) }
}
