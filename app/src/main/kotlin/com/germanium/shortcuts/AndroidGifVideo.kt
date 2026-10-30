package com.germanium.shortcuts

import android.graphics.*
import android.media.*
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Streams one decoded GIF frame at a time into the platform AVC encoder. */
internal class AndroidGifVideo(private val app:ShortcutsApplication) {
    suspend fun write(options:Map<String,String>,produce:suspend(suspend(Bitmap,Int)->Unit)->Unit):Value {
        val file=File.createTempFile("gif-video-",".mp4",app.cacheDir)
        var codec:MediaCodec?=null;var muxer:MediaMuxer?=null;var muxStarted=false;var track=-1
        var width=0;var height=0;var timestamp=0L
        val info=MediaCodec.BufferInfo()
        suspend fun drain(eos:Boolean) {
            val encoder=codec ?: throw Problem("err_video")
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(true) {
                currentCoroutineContext().ensureActive()
                when(val index=encoder.dequeueOutputBuffer(info,10000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if(!eos)return else require(android.os.SystemClock.elapsedRealtime()<deadline){throw Problem("err_video")}
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {require(!muxStarted){throw Problem("err_video")};track=muxer!!.addTrack(encoder.outputFormat);muxer!!.start();muxStarted=true}
                    else -> if(index>=0) {
                        try{if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0&&info.size>0){require(muxStarted){throw Problem("err_video")};val buffer=encoder.getOutputBuffer(index) ?: throw Problem("err_video");buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer!!.writeSampleData(track,buffer,info)}}
                        finally{encoder.releaseOutputBuffer(index,false)}
                        require(file.length()<=32*1024*1024){throw Problem("err_size")}
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)return
                    }
                }
            }
        }
        suspend fun inputIndex():Int {
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(true){currentCoroutineContext().ensureActive();val index=codec!!.dequeueInputBuffer(10000);if(index>=0)return index;drain(false);require(android.os.SystemClock.elapsedRealtime()<deadline){throw Problem("err_video")}}
        }
        try {
            produce { frame,delay ->
                if(codec==null) {
                    width=(frame.width/2*2).coerceAtLeast(2);height=(frame.height/2*2).coerceAtLeast(2)
                    val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE,(width*height*6).coerceIn(256000,8000000))
                        setInteger(MediaFormat.KEY_FRAME_RATE,30);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                    }
                    codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    codec!!.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec!!.start()
                    muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                drain(false)
                val index=inputIndex();val image=codec!!.getInputImage(index) ?: throw Problem("err_video_encoder")
                val scaled=Bitmap.createScaledBitmap(frame,width,height,true)
                try {
                    val pixels=IntArray(width*height);scaled.getPixels(pixels,0,width,0,0,width,height)
                    val planes=image.planes
                    require(planes.size==3){throw Problem("err_video_encoder")}
                    fun put(plane:Int,x:Int,y:Int,value:Int){val p=planes[plane];p.buffer.put(p.buffer.position()+y*p.rowStride+x*p.pixelStride,value.coerceIn(0,255).toByte())}
                    for(y in 0 until height){if(y%32==0)currentCoroutineContext().ensureActive();for(x in 0 until width){
                        val pixel=pixels[y*width+x];val r=pixel shr 16 and 255;val g=pixel shr 8 and 255;val b=pixel and 255
                        put(0,x,y,((66*r+129*g+25*b+128) shr 8)+16)
                        if(x%2==0&&y%2==0){var rr=0;var gg=0;var bb=0;for(dy in 0..1)for(dx in 0..1){val c=pixels[(y+dy)*width+x+dx];rr+=c shr 16 and 255;gg+=c shr 8 and 255;bb+=c and 255};rr/=4;gg/=4;bb/=4;put(1,x/2,y/2,((-38*rr-74*gg+112*bb+128) shr 8)+128);put(2,x/2,y/2,((112*rr-94*gg-18*bb+128) shr 8)+128)}
                    }}
                    codec!!.queueInputBuffer(index,0,width*height*3/2,timestamp,0);timestamp+=delay*1000L
                }finally{if(scaled!==frame)scaled.recycle()}
            }
            require(codec!=null){throw Problem("err_gif")}
            codec!!.queueInputBuffer(inputIndex(),0,0,timestamp,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true);require(muxStarted){throw Problem("err_video")}
            // An explicit empty EOS sample preserves the final frame's own duration.
            val end=MediaCodec.BufferInfo().apply{set(0,0,timestamp,MediaCodec.BUFFER_FLAG_END_OF_STREAM)}
            muxer!!.writeSampleData(track,java.nio.ByteBuffer.allocate(0),end)
            muxer!!.stop();muxStarted=false
            require(file.length() in 1..32*1024*1024){throw Problem("err_size")}
            val name=options["filename"]?.ifBlank{null}?:"animation-${UUID.randomUUID()}.mp4"
            require(name.endsWith(".mp4",true)){throw Problem("err_path")}
            return Value.Text(app.workspace.importBinary(options["folder"]?:"Videos",name,withContext(Dispatchers.IO){file.readBytes()}))
        }catch(e:CancellationException){throw e}catch(e:Problem){throw e}catch(e:Exception){throw Problem("err_video_encoder")}
        finally{runCatching{codec?.stop()};runCatching{codec?.release()};if(muxStarted)runCatching{muxer?.stop()};runCatching{muxer?.release()};file.delete()}
    }
}
