package com.germanium.shortcuts

import com.germanium.shortcuts.core.Problem

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.InputStream
import com.germanium.shortcuts.core.ShellProcessTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

/** Shizuku instantiates this Binder in a shell/root process, not as an Android Service. */
class RemoteShellService : IRemoteShell.Stub() {
    private data class Entry(val task: ShellProcessTask, @Volatile var result: Bundle? = null, var future: Future<*>? = null)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val executor = Executors.newFixedThreadPool(2)
    @Synchronized override fun start(token: String, command: String, timeoutMillis: Long) {
        require(command.isNotBlank() && command.length <= 32768 && timeoutMillis in 1000..120000)
        startTask(token, command, timeoutMillis)
    }
    override fun install(token: String, apk: ParcelFileDescriptor, size: Long, timeoutMillis: Long) {
        require(size in 1..1073741824L)
        startTask(token, "pm install -r -S $size -", timeoutMillis, ParcelFileDescriptor.AutoCloseInputStream(apk))
    }
    @Synchronized private fun startTask(token: String, command: String, timeoutMillis: Long, input: InputStream? = null) {
        check(entries.size < 4 && !entries.containsKey(token)) { throw Problem("err_invalid_data") }
        val entry = Entry(ShellProcessTask())
        entries[token] = entry
        entry.future = executor.submit {
            entry.result = try {
                val result = entry.task.run(listOf("/system/bin/sh", "-c", command), timeoutMillis, input)
                Bundle().apply { putBoolean("done", true); putInt("exitCode", result.exitCode); putString("stdout", result.stdout); putString("stderr", result.stderr); putBoolean("truncated", result.truncated) }
            } catch (e: Exception) {
                Bundle().apply { putBoolean("done", true); putString("error", e.message ?: e.javaClass.simpleName) }
            }
        }
    }
    override fun poll(token: String): Bundle {
        val entry = entries[token] ?: throw Problem("err_invalid_data")
        val result = entry.result ?: return Bundle().apply { putBoolean("done", false); putString("stdout", entry.task.liveStdout); putString("stderr", entry.task.liveStderr) }
        entries.remove(token, entry)
        return result
    }
    override fun cancel(token: String) { entries.remove(token)?.let { it.task.cancel(); it.future?.cancel(true) } }
    override fun destroy() { entries.keys.toList().forEach(::cancel); executor.shutdownNow(); exitProcess(0) }
}
