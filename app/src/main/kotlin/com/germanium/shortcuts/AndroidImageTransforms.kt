package com.germanium.shortcuts

import android.graphics.*
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

internal class AndroidImageTransforms(private val app: ShortcutsApplication) {
    private val content = AndroidContent(app)
    private val images = AndroidImages(app)
    suspend fun perform(operation: String, source: Value, options: Map<String, String>): Value {
        val chosen = if (options["interactive"] == "true") {
            val answer = app.interactions.ask(Interaction.ImageEdit(operation, source, options)) ?: throw kotlinx.coroutines.CancellationException("Image editing cancelled")
            val json = org.json.JSONObject(answer)
            options + json.keys().asSequence().associateWith { json.getString(it) }
        } else options
        val bitmap = render(operation, source, chosen)
        try {
            return withContext(Dispatchers.IO) {
                val lossy = operation == "compress_image" || operation == "convert_image" && chosen["imageFormat"] != "png"
                val webp = lossy && chosen["imageFormat"] == "webp"
                val format = if (!lossy) Bitmap.CompressFormat.PNG else if (!webp) Bitmap.CompressFormat.JPEG else if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else { @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP }
                val quality = if (lossy) (chosen["quality"] ?: "80").toIntOrNull()?.takeIf { it in 0..100 } ?: throw Problem("err_number") else 100
                val bytes = ByteArrayOutputStream().use { check(bitmap.compress(format, quality, it)); it.toByteArray() }
                require(bytes.size <= 32 * 1024 * 1024) { throw Problem("err_size") }
                val extension = if (!lossy) "png" else if (webp) "webp" else "jpg"
                val filename = chosen["filename"]?.takeIf(String::isNotBlank) ?: "image-${UUID.randomUUID()}.$extension"
                require(filename.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in if (extension == "jpg") listOf("jpg", "jpeg") else listOf(extension)) { throw Problem("err_path") }
                Value.Text(app.workspace.writeBinary(chosen["folder"] ?: "Images", filename, bytes))
            }
        } finally { bitmap.recycle() }
    }
    suspend fun render(operation: String, source: Value, options: Map<String, String>, preview: Boolean = false): Bitmap {
        var pending: Bitmap? = null
        try { return withContext(Dispatchers.Default) {
        val normalized = if (source is Value.Text && '\n' in source.value) Value.ListValue(source.value.lines().filter(String::isNotBlank).map { Value.Text(it) }) else source
        val refs = content.references(normalized, "images").toMutableList()
        require(refs.isNotEmpty()) { throw Problem("err_image") }
        if (operation in listOf("overlay_image", "mask_image")) options["secondary"]?.takeIf(String::isNotBlank)?.let { refs.add(1, it) }
        if (operation != "combine_images") while (refs.size > if (operation == "overlay_image" || operation == "mask_image" && options["maskShape"] == "custom") 2 else 1) refs.removeAt(refs.lastIndex)
        require(refs.size <= 16 && (operation != "overlay_image" && !(operation == "mask_image" && options["maskShape"] == "custom") || refs.size >= 2)) { throw Problem("err_image") }
        val bitmaps = mutableListOf<Bitmap>(); var pixels = 0L; var firstScale = 1f
        val budget = minOf(48_000_000L, Runtime.getRuntime().maxMemory() / 8)
        try {
            for ((index, ref) in refs.withIndex()) {
                coroutineContext.ensureActive()
                val data = images.bytes(ref); val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { throw Problem("err_image") }
                val decode = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.ARGB_8888; if (preview) while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 768) inSampleSize *= 2 }
                val estimate = (bounds.outWidth.toLong() / decode.inSampleSize) * (bounds.outHeight / decode.inSampleSize)
                require(pixels + estimate <= budget) { throw Problem("err_image") }
                val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, decode) ?: throw Problem("err_image")
                val orientation = runCatching { java.io.ByteArrayInputStream(data).use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
                var bitmap = decoded
                try {
                    if (orientation in 2..8) {
                        require(pixels + estimate * 2 <= budget) { throw Problem("err_image") }
                        val matrix = Matrix().apply { when (orientation) {
                            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
                        } }
                        bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (bitmap !== decoded) decoded.recycle()
                    }
                    bitmaps += bitmap; pixels += bitmap.width.toLong() * bitmap.height
                } catch (e: Throwable) { decoded.recycle(); if (bitmap !== decoded) bitmap.recycle(); throw e }
                if (index == 0) firstScale = bitmap.width.toFloat() / if (orientation in 5..8) bounds.outHeight else bounds.outWidth
            }
            val base = bitmaps.first(); val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            fun integer(key: String, default: Int, min: Int, max: Int) = (options[key]?.toIntOrNull() ?: if (options[key].isNullOrBlank()) default else throw Problem("err_number")).also { require(it in min..max) { throw Problem("err_number") } }
            fun output(width: Int, height: Int, buffers: Float = 1f): Bitmap {
                require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 16_000_000 && pixels + width.toLong() * height * buffers <= budget) { throw Problem("err_image") }
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { pending = it }
            }
            when (operation) {
                "rotate_image" -> {
                    val rotation = integer("rotation",90,-360,360)
                    val matrix = Matrix().apply { setRotate(rotation.toFloat()) }
                    val bounds = RectF(0f,0f,base.width.toFloat(),base.height.toFloat()); matrix.mapRect(bounds)
                    val width = kotlin.math.ceil(bounds.width().toDouble()-0.0001).toInt().coerceAtLeast(1)
                    val height = kotlin.math.ceil(bounds.height().toDouble()-0.0001).toInt().coerceAtLeast(1)
                    val result = output(width,height)
                    try { Canvas(result).apply { translate(-bounds.left,-bounds.top); concat(matrix); drawBitmap(base,0f,0f,paint) }; result }
                    catch (e: Exception) { result.recycle(); throw e }
                }
                "resize_image" -> {
                    val requestedWidth = integer("width", 1024, 0, 16384)
                    val requestedHeight = integer("height", 0, 0, 16384)
                    require(requestedWidth > 0 || requestedHeight > 0) { throw Problem("err_image") }
                    val originalWidth = base.width / firstScale; val originalHeight = base.height / firstScale
                    var width = requestedWidth.toFloat(); var height = requestedHeight.toFloat()
                    if (options["keepAspect"] != "false" || width == 0f || height == 0f) {
                        val scale = minOf(if (width > 0) width / originalWidth else Float.MAX_VALUE, if (height > 0) height / originalHeight else Float.MAX_VALUE)
                        width = originalWidth * scale; height = originalHeight * scale
                    }
                    val previewScale = if (preview) minOf(1f, 768f / maxOf(width, height)) else 1f
                    val result = output((width * previewScale).roundToInt().coerceAtLeast(1), (height * previewScale).roundToInt().coerceAtLeast(1))
                    try { Canvas(result).drawBitmap(base, null, Rect(0, 0, result.width, result.height), paint); result } catch (e: Exception) { result.recycle(); throw e }
                }
                "compress_image", "convert_image" -> {
                    val quality = integer("quality", 80, 0, 100)
                    val webp = options["imageFormat"] == "webp"
                    val png = options["imageFormat"] == "png"
                    require(options["imageFormat"].orEmpty() in listOf("", "jpeg", "webp", "png")) { throw Problem("err_image") }
                    val result = output(base.width, base.height)
                    try {
                        Canvas(result).apply { if (!webp && !png) drawColor(Color.WHITE); drawBitmap(base, 0f, 0f, paint) }
                        if (preview) {
                            val format = if (png) Bitmap.CompressFormat.PNG else if (!webp) Bitmap.CompressFormat.JPEG else if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else { @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP }
                            val data = ByteArrayOutputStream().use { check(result.compress(format, quality, it)); it.toByteArray() }
                            val compressed = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw Problem("err_image")
                            result.recycle(); pending = compressed; compressed
                        } else result
                    } catch (e: Exception) { result.recycle(); throw e }
                }
                "combine_images" -> {
                    val spacing = (integer("spacing", 0, 0, 1024) * firstScale).roundToInt()
                    val plan = ImageComposition.plan(bitmaps.map { ImageSize(it.width, it.height) }, options["direction"] ?: "horizontal", spacing, integer("columns", 2, 1, 16), options["matchDimensions"] != "false")
                    val result = output(plan.width, plan.height)
                    try { val canvas = Canvas(result); bitmaps.zip(plan.placements).forEach { (bitmap, p) -> canvas.drawBitmap(bitmap, null, Rect(p.x, p.y, p.x + p.width, p.y + p.height), paint) }; result }
                    catch (e: Exception) { result.recycle(); throw e }
                }
                "flip_image" -> {
                    val direction = options["flipDirection"] ?: "horizontal"; require(direction in listOf("horizontal", "vertical", "both")) { throw Problem("err_image") }
                    val result = output(base.width, base.height)
                    try { Canvas(result).apply { translate(if (direction != "vertical") base.width.toFloat() else 0f, if (direction != "horizontal") base.height.toFloat() else 0f); scale(if (direction != "vertical") -1f else 1f, if (direction != "horizontal") -1f else 1f); drawBitmap(base, 0f, 0f, paint) }; result }
                    catch (e: Exception) { result.recycle(); throw e }
                }
                "overlay_image" -> {
                    val overlay = bitmaps[1]; val size = integer("scale", 50, 1, 400) / 100f
                    val width = base.width * size; val height = width * overlay.height / overlay.width
                    val x = (base.width - width) / 2 + integer("offsetX", 0, -16384, 16384) * firstScale
                    val y = (base.height - height) / 2 + integer("offsetY", 0, -16384, 16384) * firstScale
                    val rotation = integer("rotation", 0, -360, 360)
                    val opacity = integer("opacity", 100, 0, 100)
                    val result = output(base.width, base.height)
                    try { Canvas(result).apply { drawBitmap(base, 0f, 0f, paint); rotate(rotation.toFloat(), x + width / 2, y + height / 2); paint.alpha = opacity * 255 / 100; drawBitmap(overlay, null, RectF(x, y, x + width, y + height), paint) }; result }
                    catch (e: Exception) { result.recycle(); throw e }
                }
                "mask_image" -> {
                    val shape = options["maskShape"] ?: "ellipse"; require(shape in listOf("ellipse", "circle", "rounded", "rectangle", "custom")) { throw Problem("err_image") }
                    val radius = minOf(base.width, base.height) * integer("radius", 25, 0, 50) / 100f
                    val result = output(base.width, base.height, 2f); var mask: Bitmap? = null
                    try {
                        // Alpha-only masks are treated as paint coverage on Android and do not clear DST_IN outside the shape.
                        mask = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                        val rect = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())
                        Canvas(mask).apply { when (shape) {
                            "custom" -> {
                                if (options["maskMode"] == "luminance") paint.colorFilter = ColorMatrixColorFilter(floatArrayOf(0f,0f,0f,0f,255f, 0f,0f,0f,0f,255f, 0f,0f,0f,0f,255f, 0.2126f,0.7152f,0.0722f,0f,0f))
                                drawBitmap(bitmaps[1], null, rect, paint); paint.colorFilter = null
                            }
                            "ellipse" -> drawOval(rect, paint)
                            "circle" -> drawCircle(rect.centerX(), rect.centerY(), minOf(base.width, base.height) / 2f, paint)
                            "rounded" -> drawRoundRect(rect, radius, radius, paint)
                            else -> drawRect(rect, paint)
                        } }
                        Canvas(result).apply { drawBitmap(base, 0f, 0f, paint); paint.xfermode = PorterDuffXfermode(if (options["invert"] == "true") PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN); drawBitmap(mask, 0f, 0f, paint); paint.xfermode = null }
                        result
                    } catch (e: Exception) { result.recycle(); throw e } finally { mask?.recycle() }
                }
                else -> throw Problem("err_image")
            }
        } finally { bitmaps.forEach { it.recycle() } }
        } } catch (e: Throwable) { pending?.takeUnless { it.isRecycled }?.recycle(); throw e }
    }
}
