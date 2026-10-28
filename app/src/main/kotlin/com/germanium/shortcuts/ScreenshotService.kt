package com.germanium.shortcuts

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.germanium.shortcuts.core.Problem
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** A fresh system consent token and a short-lived foreground service for each capture. */
class ScreenshotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var active: Interaction.Capture? = null
    private var finishing = false
    private var captured = false
    private val app get() = application as ShortcutsApplication
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active != null) return START_NOT_STICKY
        val request = app.interactions.current.value as? Interaction.Capture
        if (request == null || intent?.getStringExtra("request") != request.id) { stopSelf(); return START_NOT_STICKY }
        active = request
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("shortcuts.capture", getString(R.string.ui_capture_channel), NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, "shortcuts.capture").setSmallIcon(R.drawable.ic_shortcuts_monochrome).setContentTitle(getString(R.string.ui_action_screenshot)).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(2003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(2003, notification)
            @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data") ?: throw Problem("err_capture")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            val callback = object : MediaProjection.Callback() { override fun onStop() { if (!finishing && !captured) fail(Problem("err_capture")) } }
            projection!!.registerCallback(callback, Handler(Looper.getMainLooper()))
            val metrics = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else {
                @Suppress("DEPRECATION") val dm = android.util.DisplayMetrics().also { getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
                android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val width = metrics.width(); val height = metrics.height()
            require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000) { throw Problem("err_capture") }
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { source ->
                source.setOnImageAvailableListener({ images ->
                    val image = try { images.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return@setOnImageAvailableListener
                    if (captured || finishing) { image.close(); return@setOnImageAvailableListener }
                    captured = true
                    scope.launch {
                        try {
                            val png = withContext(Dispatchers.Default) {
                                image.use {
                                    val plane = it.planes[0]; val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
                                    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                                    try {
                                        padded.copyPixelsFromBuffer(plane.buffer)
                                        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                                        try { ByteArrayOutputStream().use { bytes -> check(cropped.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bytes.toByteArray() } }
                                        finally { if (cropped !== padded) cropped.recycle() }
                                    } finally { padded.recycle() }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (app.interactions.current.value !== request) throw CancellationException("Capture no longer requested")
                            val path = PublicScreenshots.save(app, request.filename, png)
                            app.interactions.reply(request, path); finishCapture()
                        } catch (e: Exception) { if (e is CancellationException) throw e; fail(e) } finally { image.close() }
                    }
                }, Handler(Looper.getMainLooper()))
            }
            scope.launch {
                delay(400)
                if (!finishing) try { display = projection!!.createVirtualDisplay("Shortcuts capture", width, height, resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null) }
                catch (e: Exception) { fail(e) }
            }
            scope.launch {
                repeat(120) { delay(100); if (request.result.isCompleted || app.interactions.current.value !== request) { finishCapture(); return@launch } }
                fail(Problem("err_capture"))
            }
        } catch (e: Exception) { fail(e) }
        return START_NOT_STICKY
    }
    private fun fail(error: Throwable) { app.diagnostics.trace("screenshot_failed", error); active?.result?.completeExceptionally(if (error is Problem) error else Problem("err_capture")); finishCapture() }
    private fun finishCapture() {
        if (finishing) return
        finishing = true
        display?.release(); display = null; reader?.close(); reader = null; projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { if (active?.result?.isCompleted == false) active?.result?.completeExceptionally(Problem("err_capture")); finishCapture(); scope.cancel(); super.onDestroy() }
}
