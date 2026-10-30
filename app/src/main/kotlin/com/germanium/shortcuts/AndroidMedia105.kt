package com.germanium.shortcuts

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.bumptech.glide.gifdecoder.*
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidMedia105(private val app:ShortcutsApplication) {
    private val images=AndroidImages(app)
    private val content=AndroidContent(app)
    private fun integer(p:Map<String,String>,key:String,default:Int,range:IntRange)= (p[key]?.toIntOrNull() ?: if(p[key].isNullOrBlank())default else throw Problem("err_number")).also { require(it in range) { throw Problem("err_number") } }
    private fun refs(value:Value,allowEmpty:Boolean=false):List<String> {
        val normalized=if(value is Value.Text&&'\n' in value.value)Value.ListValue(value.value.lines().filter(String::isNotBlank).map{Value.Text(it)})else value
        return content.references(normalized,"files",256).also { require(allowEmpty||it.isNotEmpty()) { throw Problem("err_image") } }
    }
    suspend fun perform(operation:String,source:Value,secondary:Value,p:Map<String,String>):Value=withContext(Dispatchers.Default) {
        when(operation) {
            "image_from_rich_text" -> richImage(source,p)
            "filter_images" -> filter(source,p)
            "images_from_pdf" -> pdf(source,p)
            "extract_text_from_image" -> ocr(source,p)
            "make_gif","add_frames_to_gif" -> gif(operation,source,secondary,p)
            "frames_from_image" -> extractFrames(source,p)
            "video_from_gif" -> {
                val ref=refs(source).singleOrNull() ?: throw Problem("err_gif")
                val data=images.bytes(ref);require(isGif(data)) { throw Problem("err_gif") }
                AndroidGifVideo(app).write(p) { consume -> frames(ref,120,1280,consume) }
            }
            else -> throw Problem("err_invalid_data")
        }
    }
    private suspend fun richImage(source:Value,p:Map<String,String>):Value {
        val rich=if(source is Value.RichText)source else RichTexts.fromHtml(RichTexts.html(source))
        val width=integer(p,"width",1024,32..4096);val padding=integer(p,"padding",24,0..512)
        require(width>padding*2) { throw Problem("err_image") }
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=integer(p,"fontSize",32,4..256).toFloat()}
        val styled=Html.fromHtml(rich.html,Html.FROM_HTML_MODE_COMPACT)
        val layout=StaticLayout.Builder.obtain(styled,0,styled.length,paint,width-padding*2).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val height=layout.height+padding*2
        require(width.toLong()*height<=minOf(16_000_000L,Runtime.getRuntime().maxMemory()/12)) { throw Problem("err_image") }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {Canvas(bitmap).apply{drawColor(Color.WHITE);translate(padding.toFloat(),padding.toFloat());layout.draw(this)};return save(bitmap,p["folder"]?:"Images",p["filename"]?.ifBlank{null}?:"rich-${UUID.randomUUID()}.png")}
        finally {bitmap.recycle()}
    }
    private suspend fun save(bitmap:Bitmap,folder:String,name:String):Value.Text {
        require(name.endsWith(".png",true)) { throw Problem("err_path") }
        val bytes=ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.toByteArray() }
        return Value.Text(app.workspace.writeBinary(folder,name,bytes))
    }
    private suspend fun filter(source:Value,p:Map<String,String>):Value {
        val found=mutableListOf<Pair<Value.Text,Map<String,Value>>>()
        for(ref in refs(source,true)) {
            currentCoroutineContext().ensureActive()
            val info=try{(images.perform("image_info",ref,emptyMap()) as Value.Dictionary).entries}catch(e:CancellationException){throw e}catch(e:Exception){continue}
            val width=info.getValue("width").number();val height=info.getValue("height").number()
            val property=p["property"]?:"width"
            val left=if(property=="orientation")Value.Text(if(width==height)"square" else if(width>height)"landscape" else "portrait") else info[property] ?: throw Problem("err_invalid_data")
            if(MediaParameters.matches(left,p["operator"]?:"greater",p["compare"]?:"0")) found+=Value.Text(ref) to info
        }
        val sort=p["sort"]?:"original"
        if(sort!="original")found.sortWith { a,b ->
            val x=a.second[sort] ?: throw Problem("err_invalid_data");val y=b.second.getValue(sort)
            if(x is Value.Number&&y is Value.Number)x.value.compareTo(y.value) else x.text().compareTo(y.text(),true)
        }
        if(p["descending"]=="true")found.reverse()
        return Value.ListValue(found.take(integer(p,"limit",256,1..256)).map{it.first})
    }
    private suspend fun pdf(source:Value,p:Map<String,String>):Value {
        val references=refs(source);require(references.size==1) { throw Problem("err_pdf") }
        val data=images.bytes(references.single());val temporary=File.createTempFile("pdf-pages-",".pdf",app.cacheDir)
        val written=mutableListOf<Value.Text>();var total=0L
        val folder=(p["folder"]?:"PDF Images")+"/pdf-${UUID.randomUUID()}"
        try {
            withContext(Dispatchers.IO){temporary.writeBytes(data)}
            ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                val pages=MediaParameters.pages(p["pages"]?:"all",renderer.pageCount)
                for(index in pages) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val width=integer(p,"width",1440,32..4096);val height=(width.toDouble()*page.height/page.width).toInt().coerceAtLeast(1)
                        require(width.toLong()*height<=minOf(16_000_000L,Runtime.getRuntime().maxMemory()/12)) { throw Problem("err_image") }
                        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                        try {bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val saved=save(bitmap,folder,"page-${index+1}.png");written+=saved
                            total+=app.workspace.readableFile(saved.value).length();require(total<=32*1024*1024) { throw Problem("err_size") }
                        }finally{bitmap.recycle()}
                    }
                }
            } }
            return Value.ListValue(written)
        }catch(e:Exception){written.forEach{runCatching{app.workspace.streamFile(it.value).delete()}};throw e}
        finally{temporary.delete()}
    }
    private suspend fun ocr(source:Value,p:Map<String,String>):Value {
        val result=StringBuilder()
        for(ref in refs(source)) {
            currentCoroutineContext().ensureActive()
            val bitmap=AndroidImageTransforms(app).render("resize_image",Value.Text(ref),mapOf("width" to "2048","height" to "2048","keepAspect" to "true"))
            val recognizer=when(p["language"]?:"chinese") {
                "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "latin" -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                else -> {bitmap.recycle();throw Problem("err_invalid_data")}
            }
            val text=suspendCancellableCoroutine<String> { continuation ->
                try {recognizer.process(InputImage.fromBitmap(bitmap,0))
                    .addOnSuccessListener {if(continuation.isActive)continuation.resume(it.text)}
                    .addOnFailureListener {if(continuation.isActive)continuation.resumeWithException(Problem("err_ocr"))}
                    .addOnCompleteListener {bitmap.recycle();recognizer.close()}
                }catch(e:Exception){bitmap.recycle();recognizer.close();if(continuation.isActive)continuation.resumeWithException(e)}
            }
            if(result.isNotEmpty())result.append('\n');result.append(text);require(result.length<=262144) { throw Problem("err_size") }
        }
        return Value.Text(result.toString())
    }
    private fun isGif(data:ByteArray)=data.size>=6&&(String(data,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a"))
    private val provider=object:GifDecoder.BitmapProvider {
        override fun obtain(width:Int,height:Int,config:Bitmap.Config)=Bitmap.createBitmap(width,height,config)
        override fun release(bitmap:Bitmap){bitmap.recycle()}
        override fun obtainByteArray(size:Int)=ByteArray(size)
        override fun release(bytes:ByteArray){}
        override fun obtainIntArray(size:Int)=IntArray(size)
        override fun release(array:IntArray){}
    }
    private suspend fun frames(ref:String,maxFrames:Int,edge:Int,consume:suspend(Bitmap,Int)->Unit) {
        require(maxFrames>0){throw Problem("err_size")}
        val data=images.bytes(ref)
        if(isGif(data)) {
            val parser=GifHeaderParser().setData(data)
            val header=try{parser.parseHeader()}finally{parser.clear()}
            require(header.status==GifDecoder.STATUS_OK&&header.numFrames in 1..maxFrames&&header.width.toLong()*header.height in 1..16_000_000) { throw Problem("err_gif") }
            var sample=1;while(maxOf(header.width,header.height)/sample>edge)sample*=2
            val decoder=StandardGifDecoder(provider,header,ByteBuffer.wrap(data),sample)
            try{repeat(header.numFrames){currentCoroutineContext().ensureActive();decoder.advance();val bitmap=decoder.nextFrame ?: throw Problem("err_gif");try{require(decoder.status==GifDecoder.STATUS_OK){throw Problem("err_gif")};consume(bitmap,decoder.nextDelay.coerceIn(10,60000))}finally{bitmap.recycle()}}}
            finally{decoder.clear()}
        } else if(content.mime(ref).startsWith("video/")) {
            val retriever=MediaMetadataRetriever()
            try{
                if(ref.startsWith("content://"))retriever.setDataSource(app,Uri.parse(ref)) else retriever.setDataSource(app.workspace.readableFile(ref).absolutePath)
                val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw Problem("err_video")
                require(duration>0){throw Problem("err_video")}
                val count=minOf(maxFrames,((duration+99)/100).toInt().coerceAtLeast(1))
                val videoWidth=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: throw Problem("err_video")
                val videoHeight=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: throw Problem("err_video")
                require(videoWidth.toLong()*videoHeight in 1..16_000_000){throw Problem("err_size")}
                for(index in 0 until count){
                    currentCoroutineContext().ensureActive()
                    val frame=if(android.os.Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(index*100000L,MediaMetadataRetriever.OPTION_CLOSEST,edge,edge) else retriever.getFrameAtTime(index*100000L,MediaMetadataRetriever.OPTION_CLOSEST)
                    require(frame!=null){throw Problem("err_video")}
                    try{consume(frame,100)}finally{frame.recycle()}
                }
            }finally{retriever.release()}
        } else {
            val bitmap=AndroidImageTransforms(app).render("resize_image",Value.Text(ref),mapOf("width" to "$edge","height" to "$edge","keepAspect" to "true"))
            try{consume(bitmap,100)}finally{bitmap.recycle()}
        }
    }
    private suspend fun gif(operation:String,source:Value,secondary:Value,p:Map<String,String>):Value {
        val width=integer(p,"width",512,16..1024);val height=integer(p,"height",512,16..1024)
        require(width.toLong()*height<=minOf(1_048_576L,Runtime.getRuntime().maxMemory()/1024)){throw Problem("err_size")}
        val maximum=integer(p,"maxFrames",120,1..120);val delay=integer(p,"frameDelay",100,10..60000);val loops=integer(p,"loopCount",0,0..65535)
        val buffer=ByteArrayOutputStream();val output=object:OutputStream(){override fun write(b:Int){require(buffer.size()<32*1024*1024){throw Problem("err_size")};buffer.write(b)};override fun write(b:ByteArray,off:Int,len:Int){require(buffer.size().toLong()+len<=32*1024*1024){throw Problem("err_size")};buffer.write(b,off,len)}}
        val encoder=GifEncoder(output,width,height,loops);var count=0
        suspend fun append(ref:String,preserveDelay:Boolean) {frames(ref,maximum-count,maxOf(width,height)) { frame,frameDelay ->
            require(++count<=maximum){throw Problem("err_size")};currentCoroutineContext().ensureActive()
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try{Canvas(bitmap).apply{drawColor(Color.WHITE);val scale=minOf(width.toFloat()/frame.width,height.toFloat()/frame.height);val w=frame.width*scale;val h=frame.height*scale;drawBitmap(frame,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))}
                val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height)
                encoder.addImage(pixels,width,ImageOptions().setDelay((if(preserveDelay)frameDelay else delay).toLong(),TimeUnit.MILLISECONDS))
            }finally{bitmap.recycle()}
        }}
        val originals=refs(source)
        if(operation=="add_frames_to_gif") {require(originals.size==1&&isGif(images.bytes(originals.single()))){throw Problem("err_gif")};append(originals.single(),true);for(ref in refs(secondary))append(ref,false)}
        else for(ref in originals)append(ref,isGif(images.bytes(ref)))
        require(count>0){throw Problem("err_gif")};encoder.finishEncoding()
        val name=p["filename"]?.ifBlank{null}?:"animation-${UUID.randomUUID()}.gif";require(name.endsWith(".gif",true)){throw Problem("err_path")}
        return Value.Text(app.workspace.importBinary(p["folder"]?:"GIFs",name,buffer.toByteArray()))
    }
    private suspend fun extractFrames(source:Value,p:Map<String,String>):Value {
        val written=mutableListOf<Value.Text>();var bytes=0L;val maximum=integer(p,"maxFrames",120,1..120)
        val folder=(p["folder"]?:"Frames")+"/frames-${UUID.randomUUID()}"
        try{for(ref in refs(source))frames(ref,maximum-written.size,1280){bitmap,_->
            require(written.size<maximum){throw Problem("err_size")};val file=save(bitmap,folder,"frame-${written.size+1}.png");written+=file
            bytes+=app.workspace.readableFile(file.value).length();require(bytes<=32*1024*1024){throw Problem("err_size")}
        };return Value.ListValue(written)}catch(e:Exception){written.forEach{runCatching{app.workspace.streamFile(it.value).delete()}};throw e}
    }
}
