package com.germanium.shortcuts

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Always writes synchronously before delegating a fatal error to Android's original handler. */
class Diagnostics(context: Context) {
    val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
    private val fallback = File(context.filesDir, "logs")
    private val handling = AtomicBoolean(false)
    @Volatile var activeStep: String = "none"
    /** Ordinary operations and handled failures never write diagnostic files. */
    @Suppress("UNUSED_PARAMETER") fun trace(message: String, failure: Throwable? = null) = Unit
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (handling.compareAndSet(false, true)) writeCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable) ?: run { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    fun writeCrash(thread: Thread, throwable: Throwable): File? {
        val data = "Shortcuts 0.7.0\nSDK=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}\nThread=${thread.name}\nStep=$activeStep\nTime=${stamp()}\n${throwable.stackTraceToString()}"
        for (parent in listOf(directory, fallback)) {
            val saved = runCatching {
                parent.mkdirs()
                val file = File(parent, "crash-${System.currentTimeMillis()}.log")
                java.io.FileOutputStream(file).use { it.write(data.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                parent.listFiles { candidate -> candidate.name.startsWith("crash-") }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
                file
            }.getOrNull()
            if (saved != null) return saved
        }
        return null
    }
    fun read(): String = runCatching {
        val files = (directory.listFiles().orEmpty().toList() + fallback.listFiles().orEmpty()).filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".log") }.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }.take(8)
        buildString { files.forEach { file -> append("\n--- ${file.name} ---\n"); file.inputStream().use { append(it.readBytesBounded(131072, false).toString(Charsets.UTF_8)) } } }
    }.getOrDefault("")
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
}

fun java.io.InputStream.readBytesBounded(limit: Int, strict: Boolean = true): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val available = limit + (if (strict) 1 else 0) - output.size(); if (available <= 0) break; val count = read(buffer, 0, minOf(buffer.size, available)); if (count < 0) break; output.write(buffer, 0, count); if (strict && output.size() > limit) throw com.germanium.shortcuts.core.Problem("err_size") }
    return output.toByteArray()
}
