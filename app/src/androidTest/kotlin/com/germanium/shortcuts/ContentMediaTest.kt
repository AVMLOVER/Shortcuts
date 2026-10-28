package com.germanium.shortcuts

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Compiled on the host; run only when device testing is explicitly enabled. */
@RunWith(AndroidJUnit4::class)
class ContentMediaTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private suspend fun image(width: Int, height: Int, colors: IntArray, files: MutableList<String>): String {
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        try { return app.workspace.writeBinary("MediaTests", "${UUID.randomUUID()}.png", ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }).also { files += it } }
        finally { bitmap.recycle() }
    }
    private suspend fun check(operation: String, source: Value, options: Map<String, String>, files: MutableList<String>, inspect: (Bitmap) -> Unit) {
        val path = AndroidImageTransforms(app).perform(operation, source, options).text().also { files += it }
        val bitmap = AndroidImages(app).bytes(path).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        try { inspect(bitmap) } finally { bitmap.recycle() }
    }
    private fun cleanup(files: List<String>) { files.forEach { runCatching { app.workspace.readableFile(it).delete() } } }
    @Test fun flipMovesPixelsAcrossTheHorizontalAxis() = runBlocking {
        val files = mutableListOf<String>()
        try { val input = image(2, 1, intArrayOf(Color.RED, Color.BLUE), files)
            check("flip_image", Value.Text(input), mapOf("flipDirection" to "horizontal"), files) { assertEquals(Color.BLUE, it.getPixel(0, 0)); assertEquals(Color.RED, it.getPixel(1, 0)) }
        } finally { cleanup(files) }
    }
    @Test fun ellipseMaskCreatesTransparentCorners() = runBlocking {
        val files = mutableListOf<String>()
        try { val input = image(20, 20, IntArray(400) { Color.RED }, files)
            check("mask_image", Value.Text(input), mapOf("maskShape" to "ellipse"), files) { assertEquals(0, Color.alpha(it.getPixel(0, 0))); assertEquals(255, Color.alpha(it.getPixel(10, 10))) }
        } finally { cleanup(files) }
    }
    @Test fun overlayKeepsTheBaseAndCentersTheSecondImage() = runBlocking {
        val files = mutableListOf<String>()
        try { val base = image(4, 4, IntArray(16) { Color.BLUE }, files); val overlay = image(2, 2, IntArray(4) { Color.RED }, files)
            check("overlay_image", Value.ListValue(listOf(Value.Text(base), Value.Text(overlay))), mapOf("scale" to "50", "opacity" to "100"), files) { assertEquals(Color.BLUE, it.getPixel(0, 0)); assertEquals(Color.RED, it.getPixel(1, 1)) }
        } finally { cleanup(files) }
    }
    @Test fun combineLeavesTransparentSpacingBetweenImages() = runBlocking {
        val files = mutableListOf<String>()
        try { val red = image(4, 4, IntArray(16) { Color.RED }, files); val blue = image(4, 4, IntArray(16) { Color.BLUE }, files)
            check("combine_images", Value.ListValue(listOf(Value.Text(red), Value.Text(blue))), mapOf("direction" to "horizontal", "spacing" to "1"), files) { assertEquals(9, it.width); assertEquals(4, it.height); assertEquals(0, Color.alpha(it.getPixel(4, 0))); assertEquals(Color.BLUE, it.getPixel(5, 0)) }
        } finally { cleanup(files) }
    }
    @Test fun textExtractionReadsSharedFileBytes() = runBlocking {
        val path = app.workspace.importBinary("MediaTests", "${UUID.randomUUID()}.txt", "hello 12.5".toByteArray())
        try { assertEquals("hello 12.5", AndroidContent(app).extract("text", Value.Text(path)).text()) } finally { cleanup(listOf(path)) }
    }
    @Test fun outboundFilesUseReadGrantedContentUris() = runBlocking {
        val files = mutableListOf<String>()
        try { val path = image(1, 1, intArrayOf(Color.RED), files)
            val chooser = AndroidContent(app).shareIntent(Value.Text(path))
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(Intent.ACTION_SEND, send.action); assertEquals("image/png", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals("content", uri.scheme)
            assertEquals("${app.packageName}.files", uri.authority)
        } finally { cleanup(files) }
    }
}
