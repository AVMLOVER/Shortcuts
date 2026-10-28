package com.germanium.shortcuts

import com.germanium.shortcuts.core.Problem

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import com.germanium.shortcuts.core.ShellResult
import kotlinx.coroutines.*
import java.util.UUID

import rikka.shizuku.Shizuku

class ShizukuShellBackend(context: Context) {
    private val component = ComponentName(context, RemoteShellService::class.java)
    suspend fun execute(command: String, timeoutMillis: Long, apk: File? = null, onOutput: (String, String) -> Unit = { _, _ -> }): ShellResult {
        checkPermission()
        val ready = CompletableDeferred<IRemoteShell>()
        val args = Shizuku.UserServiceArgs(component).daemon(false).processNameSuffix("shortcuts_shell").tag("shortcuts.shell").version(3)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IRemoteShell.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { if (!ready.isCompleted) ready.completeExceptionally(Problem("shizuku_unavailable")) }
        }
        var remote: IRemoteShell? = null
        val token = UUID.randomUUID().toString()
        try {
            withContext(Dispatchers.Main.immediate) { Shizuku.bindUserService(args, connection) }
            remote = withTimeout(10000) { ready.await() }
            val service = remote
            return withContext(Dispatchers.IO) {
                checkPermission()
                if (apk == null) service.start(token, command, timeoutMillis)
                else ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { service.install(token, it, apk.length(), timeoutMillis) }
                withTimeout(timeoutMillis + 5000) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkPermission()
                        val result = service.poll(token)
                        onOutput(result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty())
                        if (result.getBoolean("done")) {
                            result.getString("error")?.let { error(it) }
                            return@withTimeout ShellResult(result.getInt("exitCode"), result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty(), result.getBoolean("truncated"))
                        }
                        delay(100)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw Problem("shizuku_unavailable")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { remote?.cancel(token) } }
            withContext(NonCancellable + Dispatchers.Main.immediate) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
        }
    }
    private fun checkPermission() {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { throw Problem("shizuku_unavailable") }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { throw Problem("shizuku_unavailable") }
    }
}
