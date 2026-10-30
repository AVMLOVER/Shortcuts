package com.germanium.shortcuts

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Uses isolated generated fixtures, never user workflows or privileged system operations. */
@RunWith(AndroidJUnit4::class)
class Version105DeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private val files=mutableListOf<String>()
    private suspend fun image(width:Int,height:Int,color:Int):Value.Text {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try{bitmap.eraseColor(color);return save(bitmap)}finally{bitmap.recycle()}
    }
    private suspend fun save(bitmap:Bitmap):Value.Text {
        val bytes=ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}
        return Value.Text(app.workspace.writeBinary("Test105","${UUID.randomUUID()}.png",bytes).also{files+=it})
    }
    private fun remember(value:Value):Value {ContentInput.flatten(value).forEach{files+=it.text()};return value}
    private suspend fun perform(type:String,source:Value,options:Map<String,String> = emptyMap(),secondary:Value=Value.Null)=AndroidMedia105(app).perform(type,source,secondary,options)
    private fun cleanup(){files.forEach{runCatching{app.workspace.streamFile(it).delete()}}}
    @Test fun gifCreationAppendAndFrameExtractionPreserveFrameOrder():Unit=runBlocking {
        try{
            val red=image(128,128,Color.RED);val blue=image(128,128,Color.BLUE)
            val options=mapOf("width" to "128","height" to "128","frameDelay" to "100","folder" to "Test105")
            val gif=remember(perform("make_gif",Value.ListValue(listOf(red,blue)),options))
            val bytes=AndroidImages(app).bytes(gif.text());val parser=GifHeaderParser().setData(bytes);val header=try{parser.parseHeader()}finally{parser.clear()}
            assertEquals(2,header.numFrames);assertEquals(128,header.width)
            val extracted=remember(perform("frames_from_image",gif,mapOf("folder" to "Test105"))) as Value.ListValue
            assertEquals(2,extracted.items.size)
            extracted.items.forEachIndexed{index,item->val data=AndroidImages(app).bytes(item.text());val bitmap=BitmapFactory.decodeByteArray(data,0,data.size);try{val pixel=bitmap.getPixel(64,64);if(index==0)assertTrue(Color.red(pixel)>200)else assertTrue(Color.blue(pixel)>200)}finally{bitmap.recycle()}}
            val green=image(128,128,Color.GREEN)
            val appended=remember(perform("add_frames_to_gif",gif,options,green))
            val appendParser=GifHeaderParser().setData(AndroidImages(app).bytes(appended.text()))
            try{assertEquals(3,appendParser.parseHeader().numFrames)}finally{appendParser.clear()}
        }finally{cleanup()}
    }
    @Test fun gifVideoIsAReadableMp4WithTimingAndDimensions():Unit=runBlocking {
        try{
            val red=image(128,128,Color.RED);val blue=image(128,128,Color.BLUE)
            val first=remember(perform("make_gif",red,mapOf("width" to "128","height" to "128","frameDelay" to "100","folder" to "Test105")))
            val gif=remember(perform("add_frames_to_gif",first,mapOf("width" to "128","height" to "128","frameDelay" to "300","folder" to "Test105"),blue))
            val video=remember(perform("video_from_gif",gif,mapOf("folder" to "Test105")))
            val reader=MediaMetadataRetriever()
            try{reader.setDataSource(app.workspace.readableFile(video.text()).absolutePath);assertEquals("128",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 380..500);val frame=reader.frameAtTime;assertNotNull(frame);frame?.recycle()}finally{reader.release()}
        }finally{cleanup()}
    }
    @Test fun pdfSelectedPageRendersTheRequestedContent():Unit=runBlocking {
        try{
            val document=PdfDocument();val bytes=try{for(index in 1..2){val page=document.startPage(PdfDocument.PageInfo.Builder(200,100,index).create());page.canvas.drawColor(if(index==1)Color.RED else Color.BLUE);document.finishPage(page)};ByteArrayOutputStream().use{document.writeTo(it);it.toByteArray()}}finally{document.close()}
            val source=Value.Text(app.workspace.importBinary("Test105","${UUID.randomUUID()}.pdf",bytes).also{files+=it})
            val result=remember(perform("images_from_pdf",source,mapOf("pages" to "2","width" to "200","folder" to "Test105"))) as Value.ListValue
            assertEquals(1,result.items.size);val data=AndroidImages(app).bytes(result.items.single().text());val bitmap=BitmapFactory.decodeByteArray(data,0,data.size)
            try{assertEquals(200,bitmap.width);assertEquals(100,bitmap.height);assertTrue(Color.blue(bitmap.getPixel(100,50))>200)}finally{bitmap.recycle()}
        }finally{cleanup()}
    }
    @Test fun richTextDrawsFormattingAndSharesHtml():Unit=runBlocking {
        try{
            val rich=RichTexts.fromHtml("<h1>Shortcuts</h1><p><b>Bold</b> and <i>Italic</i></p>")
            val path=remember(perform("image_from_rich_text",rich,mapOf("width" to "320","fontSize" to "24","folder" to "Test105")))
            assertEquals("320",(AndroidImages(app).perform("image_info",path.text(),emptyMap()) as Value.Dictionary).entries.getValue("width").text())
            val chooser=AndroidContent(app).shareIntent(rich);val send=chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals("text/html",send.type);assertTrue(send.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT)!!.contains("<b>"))
        }finally{cleanup()}
    }
    @Test fun createdRichTextFilesPreviewTheirContentInsteadOfXmlSource():Unit=runBlocking {
        try{
            val rich=RichTexts.fromMarkdown("# Saved Rich Text\n\n**Bold** content")
            val path=app.workspace.perform("write_file","Test105","${UUID.randomUUID()}.xml",ValueDocument.encode(rich)).text().also{files+=it}
            val preview=AndroidPreviewContent(app).load(path)
            assertEquals(rich,preview?.typedContent);assertNull(preview?.document?.text)
        }finally{cleanup()}
    }
    @Test fun filteringUsesNumericDimensions():Unit=runBlocking {
        try{val wide=image(64,16,Color.RED);val tall=image(16,64,Color.BLUE)
            val result=perform("filter_images",Value.ListValue(listOf(wide,tall)),mapOf("property" to "width","operator" to "greater","compare" to "32"))
            assertEquals(Value.ListValue(listOf(wide)),result)
        }finally{cleanup()}
    }
    @Test fun bundledLatinOcrReadsGeneratedText():Unit=runBlocking {
        try{val bitmap=Bitmap.createBitmap(900,180,Bitmap.Config.ARGB_8888)
            val source=try{Canvas(bitmap).apply{drawColor(Color.WHITE);drawText("SHORTCUTS 105",24f,120f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=84f;typeface=Typeface.MONOSPACE})};save(bitmap)}finally{bitmap.recycle()}
            val result=perform("extract_text_from_image",source,mapOf("language" to "latin"))
            assertTrue(result.text(),result.text().uppercase().contains("SHORTCUTS"))
        }finally{cleanup()}
    }
    @Test fun bundledChineseAndJapaneseOcrReadTheirScripts():Unit=runBlocking {
        try{for((language,text) in listOf("chinese" to "快捷指令123","japanese" to "ショートカット123")) {
            val bitmap=Bitmap.createBitmap(1200,180,Bitmap.Config.ARGB_8888)
            val source=try{Canvas(bitmap).apply{drawColor(Color.WHITE);drawText(text,24f,120f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=84f})};save(bitmap)}finally{bitmap.recycle()}
            val output=perform("extract_text_from_image",source,mapOf("language" to language)).text().replace(Regex("\\s+"),"")
            assertTrue("$language: $output",output.contains(text))
        }}finally{cleanup()}
    }
    @Test fun renamingAWorkspaceFileReturnsItsNewReadableReference():Unit=runBlocking {
        try{val source=app.workspace.importBinary("Test105","${UUID.randomUUID()}.bin",byteArrayOf(0,1,2,-1)).also{files+=it}
            val renamed=app.actions.renameFile(source,"${UUID.randomUUID()}.dat",ExecutionMode.STANDARD).text().also{files+=it}
            assertFalse(java.io.File(app.getExternalFilesDir(null),"workspace/$source").exists())
            assertArrayEquals(byteArrayOf(0,1,2,-1),AndroidImages(app).bytes(renamed))
        }finally{cleanup()}
    }
}
