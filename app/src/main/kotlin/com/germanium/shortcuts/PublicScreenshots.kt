package com.germanium.shortcuts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.germanium.shortcuts.core.Problem
import kotlinx.coroutines.*
import java.io.File

/** Publishes a complete PNG in Pictures/Screenshots, never in application-private storage. */
internal object PublicScreenshots {
    suspend fun save(context: Context, filename: String, png: ByteArray): String = withContext(Dispatchers.IO) {
        require(filename.isNotBlank() && filename.length <= 200 && filename.endsWith(".png", true) && filename.none { it == '/' || it == '\\' || it.code < 32 }) { throw Problem("err_path") }
        require(png.isNotEmpty() && png.size <= 32 * 1024 * 1024) { throw Problem("err_size") }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots/")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw Problem("err_io")
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(png) } ?: throw Problem("err_io")
                currentCoroutineContext().ensureActive()
                require(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { throw Problem("err_io") }
                uri.toString()
            } catch (error: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw error }
        } else {
            @Suppress("DEPRECATION") val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
            require(folder.isDirectory || folder.mkdirs()) { throw Problem("err_io") }
            var created: File? = null
            for (index in 0..999) {
                val candidate = File(folder, if (index == 0) filename else "${filename.dropLast(4)} ($index).png")
                if (candidate.createNewFile()) { created = candidate; break }
            }
            val file = created ?: throw Problem("err_io")
            try {
                file.outputStream().use { it.write(png) }; currentCoroutineContext().ensureActive()
                @Suppress("DEPRECATION") val dataColumn = MediaStore.Images.Media.DATA
                values.put(dataColumn, file.absolutePath); values.put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                (resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw Problem("err_io")).toString()
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }
}
