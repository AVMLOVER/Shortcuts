package com.germanium.shortcuts

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.germanium.shortcuts.core.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

internal class AndroidExtras104(private val app: ShortcutsApplication) {
    suspend fun generateQr(text: String, size: Int, correction: String): Value = withContext(Dispatchers.Default) {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 2953 && size in 128..2048 && correction in listOf("L","M","Q","H")) { throw Problem("err_qr") }
        val matrix = try { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.valueOf(correction), EncodeHintType.MARGIN to 4)) } catch (_: WriterException) { throw Problem("err_qr") }
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888)
        try {
            val bytes = ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)); it.toByteArray() }
            Value.Text(app.workspace.writeBinary("Images","qr-${UUID.randomUUID()}.png",bytes))
        } finally { bitmap.recycle() }
    }
    suspend fun readBarcode(source: String): Value = withContext(Dispatchers.Default) {
        // Reuse the image loader so EXIF orientation and memory limits are applied.
        val bitmap = AndroidImageTransforms(app).render("resize_image",Value.Text(source),mapOf("width" to "2048","height" to "2048","keepAspect" to "true"))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val luminance = RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8")) }
            var result: Result? = null
            var current: LuminanceSource = luminance
            repeat(4) {
                coroutineContext.ensureActive()
                if (result == null) result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(current))) }.getOrNull()
                if (result == null) result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(current.invert()))) }.getOrNull()
                if (current.isRotateSupported) current = current.rotateCounterClockwise()
            }
            reader.reset()
            val decoded = result ?: throw Problem("err_barcode")
            Value.Text(decoded.text.takeIf { it.length <= 262144 } ?: throw Problem("err_size"))
        } finally { bitmap.recycle() }
    }
    fun volume(percent: Int?): Int {
        val audio = app.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (percent != null) { require(percent in 0..100) { throw Problem("err_number") }; audio.setStreamVolume(AudioManager.STREAM_MUSIC,(percent.toLong()*max+50).div(100).toInt(),0) }
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toLong()*100/max).toInt()
    }
    suspend fun flashlight(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            app.interactions.ask(Interaction.Permissions(arrayOf(Manifest.permission.CAMERA)))
            if (ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw Problem("err_permission")
        }
        val manager = app.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: throw Problem("err_flashlight")
        try { manager.setTorchMode(id,enabled) } catch (_: android.hardware.camera2.CameraAccessException) { throw Problem("err_flashlight") }
    }
    suspend fun speak(text: String, language: String, rate: Float, pitch: Float) = withContext(Dispatchers.Main.immediate) {
        require(text.isNotBlank() && text.length <= 262144 && rate.isFinite() && pitch.isFinite() && rate in 0.1f..3f && pitch in 0.1f..2f) { throw Problem("err_speech") }
        val ready = CompletableDeferred<Int>()
        val tts = TextToSpeech(app) { ready.complete(it) }
        try {
            if (withTimeout(15000) { ready.await() } != TextToSpeech.SUCCESS) throw Problem("err_speech")
            val locale = if (language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
            require(locale.language.isNotBlank() && tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) { throw Problem("err_speech") }
            require(tts.setSpeechRate(rate) == TextToSpeech.SUCCESS && tts.setPitch(pitch) == TextToSpeech.SUCCESS) { throw Problem("err_speech") }
            // Bound each utterance, await completion, and release the engine on cancellation.
            var start = 0
            while (start < text.length) {
                currentCoroutineContext().ensureActive()
                var end = minOf(text.length,start+(TextToSpeech.getMaxSpeechInputLength()-1).coerceAtLeast(2))
                if (end < text.length && text[end-1].isHighSurrogate()) end--
                val chunk=text.substring(start,end);start=end
                val finished = CompletableDeferred<Unit>(); val activeId = UUID.randomUUID().toString()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == activeId) finished.complete(Unit) }
                    @Deprecated("Legacy TTS callback") override fun onError(utteranceId: String?) { if (utteranceId == activeId) finished.completeExceptionally(Problem("err_speech")) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == activeId) finished.completeExceptionally(Problem("err_speech")) }
                })
                if (tts.speak(chunk,TextToSpeech.QUEUE_FLUSH,null,activeId) != TextToSpeech.SUCCESS) throw Problem("err_speech")
                withTimeout(600000) { finished.await() }
            }
        } finally { tts.stop(); tts.shutdown() }
    }
}
