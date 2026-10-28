package com.germanium.shortcuts

import android.graphics.BitmapFactory
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
        if (path.substringAfterLast('.').lowercase() !in setOf("png", "jpg", "jpeg", "webp") || path.length > 500) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = app.workspace.imageFile(path) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, t("action_screenshot"), Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("screenshot_preview")) }
}
