package com.germanium.shortcuts

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageMatrixTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private val files = mutableListOf<String>()
    private suspend fun input(width: Int, height: Int, colors: IntArray): String {
        val bitmap = Bitmap.createBitmap(colors,width,height,Bitmap.Config.ARGB_8888)
        try { return app.workspace.writeBinary("MediaTests","${UUID.randomUUID()}.png",ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }).also { files += it } }
        finally { bitmap.recycle() }
    }
    private suspend fun image(operation: String, source: Value, options: Map<String,String> = emptyMap(), inspect: (Bitmap)->Unit) {
        val path = app.actions.transformImage(operation,source,options+mapOf("folder" to "MediaTests")).text().also { files += it }
        val data=AndroidImages(app).bytes(path); val bitmap=BitmapFactory.decodeByteArray(data,0,data.size)
        try { inspect(bitmap) } finally { bitmap.recycle() }
    }
    private fun cleanup() { files.forEach { path -> runCatching { if (path.startsWith("content://") && Uri.parse(path).authority == "${app.packageName}.files") app.contentResolver.delete(Uri.parse(path),null,null) else app.workspace.readableFile(path).delete() } }; files.clear() }
    @Test fun getInfoCropAndExifOrientation() = runBlocking {
        try {
            val path=input(2,2,intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE))
            assertEquals(path,app.actions.image("get_image",path,emptyMap()).text())
            val info=app.actions.image("image_info",path,emptyMap()) as Value.Dictionary
            assertEquals("2",info.entries["width"]?.text()); assertEquals("image/png",info.entries["mimeType"]?.text())
            val cropped=app.actions.image("crop_image",path,mapOf("x" to "1","y" to "0","width" to "1","height" to "2","folder" to "MediaTests","filename" to "${UUID.randomUUID()}.png")).text().also { files+=it }
            val data=AndroidImages(app).bytes(cropped); val bitmap=BitmapFactory.decodeByteArray(data,0,data.size)
            try { assertEquals(1,bitmap.width); assertEquals(Color.GREEN,bitmap.getPixel(0,0)); assertEquals(Color.WHITE,bitmap.getPixel(0,1)) } finally { bitmap.recycle() }
            val jpeg=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
            val bytes=try { ByteArrayOutputStream().use { jpeg.compress(Bitmap.CompressFormat.JPEG,90,it); it.toByteArray() } } finally { jpeg.recycle() }
            val rotated=app.workspace.writeBinary("MediaTests","${UUID.randomUUID()}.jpg",bytes).also { files+=it }
            android.media.ExifInterface(app.workspace.readableFile(rotated).absolutePath).apply { setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6"); saveAttributes() }
            image("flip_image",Value.Text(rotated)) { assertEquals(2,it.width); assertEquals(3,it.height) }
        } finally { cleanup() }
    }
    @Test fun everyMaskShapeAndBothCustomChannels() = runBlocking {
        try {
            val source=Value.Text(input(20,20,IntArray(400){Color.RED}))
            for(shape in listOf("ellipse","circle","rounded","rectangle")) image("mask_image",source,mapOf("maskShape" to shape,"radius" to "50")) { assertEquals(255,Color.alpha(it.getPixel(10,10))); if(shape!="rectangle") assertEquals(0,Color.alpha(it.getPixel(0,0))) }
            image("mask_image",source,mapOf("invert" to "true")) { assertEquals(0,Color.alpha(it.getPixel(10,10))); assertEquals(255,Color.alpha(it.getPixel(0,0))) }
            val base=input(4,2,IntArray(8){Color.RED}); val alpha=input(4,2,IntArray(8){if(it%4<2)Color.TRANSPARENT else Color.WHITE})
            image("mask_image",Value.Text(base),mapOf("maskShape" to "custom","secondary" to alpha,"maskMode" to "alpha")) { assertEquals(0,Color.alpha(it.getPixel(0,1))); assertEquals(255,Color.alpha(it.getPixel(3,1))) }
            val luminance=input(4,2,IntArray(8){if(it%4<2)Color.BLACK else Color.WHITE})
            image("mask_image",Value.Text(base),mapOf("maskShape" to "custom","secondary" to luminance,"maskMode" to "luminance")) { assertEquals(0,Color.alpha(it.getPixel(0,1))); assertEquals(255,Color.alpha(it.getPixel(3,1))) }
        } finally { cleanup() }
    }
    @Test fun verticalBothFlipsAndAllCompositionLayoutsKeepDuplicates() = runBlocking {
        try {
            val source=input(2,2,intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE))
            image("flip_image",Value.Text(source),mapOf("flipDirection" to "vertical")) { assertEquals(Color.BLUE,it.getPixel(0,0)) }
            image("flip_image",Value.Text(source),mapOf("flipDirection" to "both")) { assertEquals(Color.WHITE,it.getPixel(0,0)) }
            val list=Value.ListValue(listOf(Value.Text(source),Value.Text(source)))
            image("combine_images",list,mapOf("direction" to "horizontal","spacing" to "1")) { assertEquals(5,it.width); assertEquals(0,Color.alpha(it.getPixel(2,0))) }
            image("combine_images",list,mapOf("direction" to "vertical","spacing" to "1")) { assertEquals(5,it.height); assertEquals(0,Color.alpha(it.getPixel(0,2))) }
            image("combine_images",Value.ListValue(list.items+Value.Text(source)),mapOf("direction" to "grid","spacing" to "1","columns" to "2")) { assertEquals(5,it.width); assertEquals(5,it.height); assertEquals(0,Color.alpha(it.getPixel(4,4))) }
        } finally { cleanup() }
    }
    @Test fun overlayOpacityRotationAndOffsets() = runBlocking {
        try {
            val base=input(6,6,IntArray(36){Color.BLUE}); val overlay=input(2,1,IntArray(2){Color.RED}); val source=Value.ListValue(listOf(Value.Text(base),Value.Text(overlay)))
            image("overlay_image",source,mapOf("opacity" to "0")) { assertEquals(Color.BLUE,it.getPixel(3,3)) }
            image("overlay_image",source,mapOf("opacity" to "50","scale" to "100")) { val color=it.getPixel(3,3); assertTrue(Color.red(color) in 126..129); assertTrue(Color.blue(color) in 126..129) }
            image("overlay_image",source,mapOf("rotation" to "90")) { assertEquals(Color.BLUE,it.getPixel(4,3)); assertTrue(Color.red(it.getPixel(3,3))>100) }
            image("overlay_image",source,mapOf("offsetX" to "2")) { assertEquals(Color.BLUE,it.getPixel(2,3)); assertTrue(Color.red(it.getPixel(5,3))>100) }
        } finally { cleanup() }
    }
    @Test fun resizeDistortionWebpAndJpegQualityExtremes() = runBlocking {
        try {
            val source=input(64,32,IntArray(2048){Color.rgb(it*31%256,it*71%256,it*101%256)})
            image("resize_image",Value.Text(source),mapOf("width" to "10","height" to "10","keepAspect" to "false")) { assertEquals(10,it.width); assertEquals(10,it.height) }
            val webp=app.actions.transformImage("compress_image",Value.Text(source),mapOf("imageFormat" to "webp","quality" to "40","folder" to "MediaTests")).text().also { files+=it }
            val data=AndroidImages(app).bytes(webp); val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(data,0,data.size,bounds); assertEquals("image/webp",bounds.outMimeType)
            val outputs=listOf("0","100").map { quality -> app.actions.transformImage("compress_image",Value.Text(source),mapOf("quality" to quality,"folder" to "MediaTests")).text().also { files+=it } }
            assertFalse(AndroidImages(app).bytes(outputs[0]).contentEquals(AndroidImages(app).bytes(outputs[1])))
        } finally { cleanup() }
    }
    @Test fun sharedImageFlowsThroughInputConversionAndTransform() = runBlocking {
        try {
            val source=input(2,1,intArrayOf(Color.RED,Color.BLUE)); val chooser=AndroidContent(app).shareIntent(Value.Text(source))
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val payload=AndroidContent(app).receive(send)
            val photos=(payload as Value.Dictionary).entries.getValue("images") as Value.ListValue; assertEquals(1,photos.items.size); files+=photos.items.map { it.text() }
            val result=app.engine.run(Shortcut(name="Verify102 image input",steps=listOf(ActionStep(type="share_input"),ActionStep(type="get_images_from_input"),ActionStep(type="flip_image",parameters=mapOf("folder" to "MediaTests")))),ExecutionMode.STANDARD,typedInput=payload)
            assertEquals(RunStatus.SUCCEEDED,result.status); files+=result.output
            val data=AndroidImages(app).bytes(result.output); val bitmap=BitmapFactory.decodeByteArray(data,0,data.size)
            try { assertEquals(Color.BLUE,bitmap.getPixel(0,0)) } finally { bitmap.recycle() }
        } finally { cleanup() }
    }
    @Test fun imageAndTextPdfPagesCanBeSavedToAContentUri() = runBlocking {
        try {
            val image=input(20,10,IntArray(200){Color.RED}); val previous=app.interactions.onPending; app.interactions.onPending=null
            val path=try { coroutineScope {
                val task=async { app.actions.transformImage("make_pdf",Value.ListValue(listOf(Value.Text("Text page"),Value.Text(image))),emptyMap()).text() }
                val request=withTimeout(15000) { app.interactions.current.first { it is Interaction.SavePdf } } as Interaction.SavePdf
                val directory=File(app.cacheDir,"shared").also { check(it.isDirectory||it.mkdirs()) }; val file=File(directory,"verify102-${UUID.randomUUID()}.pdf")
                val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file); app.interactions.reply(request,uri.toString()); task.await()
            } } finally { app.interactions.onPending=previous }
            files+=path; PdfRenderer(app.contentResolver.openFileDescriptor(Uri.parse(path),"r")!!).use { assertEquals(2,it.pageCount) }
        } finally { cleanup() }
    }
    @Test fun textImageUsesCustomColorsAndOpaqueBackground() = runBlocking {
        try { image("text_image",Value.Text("MM"),mapOf("width" to "200","fontSize" to "40","foregroundColor" to "#FF0000","backgroundColor" to "#00FF00")) {
            assertEquals(Color.GREEN,it.getPixel(0,0)); val pixels=IntArray(it.width*it.height); it.getPixels(pixels,0,it.width,0,0,it.width,it.height)
            assertTrue(pixels.any { p -> Color.red(p)>200&&Color.green(p)<100 })
        } } finally { cleanup() }
    }
}
