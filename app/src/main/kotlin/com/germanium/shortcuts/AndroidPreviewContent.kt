package com.germanium.shortcuts

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class DocumentPreview(val name:String,val mime:String,val size:Int,val text:String?=null,val page:Int=0,val pages:Int=0)
internal data class RichPreview(val image:Bitmap?=null,val document:DocumentPreview?=null,val animation:ByteArray?=null,val typedContent:Value?=null)

/** One bounded loader shared by app results, dialogs, desktop overlays and notifications. */
internal class AndroidPreviewContent(private val app:ShortcutsApplication) {
    private val content=AndroidContent(app)
    suspend fun label(source:String):String=withContext(Dispatchers.IO) {
        if(!candidate(source)) return@withContext source
        try { if(source.startsWith("content://")) content.name(Uri.parse(source)) else app.workspace.readableFile(source).name }
        catch(e:CancellationException){throw e}catch(_:Exception){source}
    }
    suspend fun load(source:String,page:Int=0,edge:Int=1200):RichPreview?=withContext(Dispatchers.IO) {
        if(!candidate(source)) return@withContext null
        val data=try{AndroidImages(app).bytes(source)}catch(e:CancellationException){throw e}catch(_:Exception){return@withContext null}
        ensureActive()
        val bitmap=try{image(data,edge)}catch(e:CancellationException){throw e}catch(_:Exception){null}
        bitmap?.let{
            val animation=if(android.os.Build.VERSION.SDK_INT>=28&&data.size>=6&&String(data,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")) {
                val parser=com.bumptech.glide.gifdecoder.GifHeaderParser().setData(data)
                val header=try{parser.parseHeader()}finally{parser.clear()}
                data.takeIf{header.status==0&&header.numFrames in 2..120&&header.width.toLong()*header.height in 1..4_000_000}
            }else null
            return@withContext RichPreview(image=it,animation=animation)
        }
        val name=label(source)
        val declared=runCatching{content.mime(source)}.getOrDefault("")
        val mime=if(data.size>=5&&data.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())) "application/pdf" else declared.takeUnless{it.isBlank()||it=="application/octet-stream"}
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        var document=DocumentPreview(name,mime,data.size)
        if(mime=="application/pdf") {
            var temporary:File?=null
            try {
                val file=File.createTempFile("preview-",".pdf",app.cacheDir).also{temporary=it};file.writeBytes(data)
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val selected=page.coerceIn(0,(renderer.pageCount-1).coerceAtLeast(0))
                        if(renderer.pageCount>0) renderer.openPage(selected).use { pdf ->
                            require(pdf.width>0&&pdf.height>0)
                            val ratio=edge.toFloat()/maxOf(pdf.width,pdf.height)
                            val bitmap=Bitmap.createBitmap((pdf.width*ratio).toInt().coerceAtLeast(1),(pdf.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            try{bitmap.eraseColor(Color.WHITE);pdf.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}catch(e:Exception){bitmap.recycle();throw e}
                            document=document.copy(page=selected,pages=renderer.pageCount)
                            return@withContext RichPreview(bitmap,document)
                        }
                    }
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){/* Keep an openable document card when native rendering is unavailable. */}
            finally{temporary?.delete()}
        }
        if(data.size<=1024*1024) {
            val text=runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()}
                .getOrNull()?.takeIf{value->value.none{it.code<32&&it !in "\t\r\n"}}
            if(text!=null) {
                val typed=if(text.startsWith(ValueDocument.HEADER))runCatching{ValueDocument.decode(text)}.getOrNull() else null
                if(typed is Value.RichText)return@withContext RichPreview(document=document,typedContent=typed)
                document=document.copy(text=typed?.text() ?: text,mime=if(mime=="application/octet-stream")"text/plain" else mime)
            }
        }
        RichPreview(document=document)
    }
    suspend fun open(source:String,mimeHint:String?=null) {
        try {
            val intent=withContext(Dispatchers.IO) {
                val uri=if(source.startsWith("content://")) Uri.parse(source) else {
                    val file=app.workspace.readableFile(source)
                    val target=File(app.cacheDir,"shared/${UUID.randomUUID()}-${file.name.take(96)}")
                    check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs());file.copyTo(target)
                    FileProvider.getUriForFile(app,"${app.packageName}.files",target)
                }
                val mime=mimeHint ?: content.mime(source).takeUnless{it=="application/octet-stream"} ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(label(source).substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
                Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK).apply{clipData=ClipData.newRawUri("Shortcuts",uri)}
            }
            withContext(Dispatchers.Main){(app.foreground()?:app).startActivity(intent)}
        }catch(e:CancellationException){throw e}catch(_:Exception){throw Problem("err_open_document")}
    }
    private fun candidate(source:String)=source.isNotEmpty()&&source.length<=4096&&'\n' !in source&&'\r' !in source
    private fun image(data:ByteArray,edge:Int):Bitmap? {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0) return null
        val options=BitmapFactory.Options().apply{inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>edge)inSampleSize*=2}
        val decoded=BitmapFactory.decodeByteArray(data,0,data.size,options)?:return null
        val orientation=runCatching{java.io.ByteArrayInputStream(data).use{android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}}.getOrDefault(1)
        if(orientation !in 2..8)return decoded
        try {
            val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)}}
            return Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{if(it!==decoded)decoded.recycle()}
        }catch(e:Exception){decoded.recycle();throw e}
    }
}
