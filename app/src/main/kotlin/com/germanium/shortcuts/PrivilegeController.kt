package com.germanium.shortcuts
import android.content.pm.PackageManager
import android.content.Context
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

data class PrivilegeStatus(val mode: ExecutionMode, val available: Boolean, val message: String)
class PrivilegeController(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("privileges", Context.MODE_PRIVATE)
    private val memory = PrivilegeMemory({ preferences.getBoolean(it.name, false) }, { mode, granted -> preferences.edit().putBoolean(mode.name, granted).apply() })
    fun rootStatus() = PrivilegeStatus(ExecutionMode.ROOT, memory.granted(ExecutionMode.ROOT), if(memory.granted(ExecutionMode.ROOT)) "privilege_authorized" else "root_check")
    fun shizukuStatus(): PrivilegeStatus = runCatching {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> shizukuDisconnected()
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                memory.record(ExecutionMode.SHIZUKU, false)
                PrivilegeStatus(ExecutionMode.SHIZUKU, false, "shizuku_permission")
            }
            else -> {
                memory.record(ExecutionMode.SHIZUKU, true)
                PrivilegeStatus(ExecutionMode.SHIZUKU, true, "privilege_authorized")
            }
        }
    }.getOrElse { shizukuDisconnected() }
    private fun shizukuDisconnected() = PrivilegeStatus(ExecutionMode.SHIZUKU, false, if(memory.granted(ExecutionMode.SHIZUKU)) "shizuku_authorized_unavailable" else "shizuku_unavailable")
    fun requestShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) throw Problem("shizuku_unavailable")
        if (Shizuku.shouldShowRequestPermissionRationale()) throw Problem("shizuku_permission")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(2048)
        else memory.record(ExecutionMode.SHIZUKU, true)
    }
    suspend fun requestRoot(): PrivilegeStatus = try {
        val result = runInterruptible(Dispatchers.IO) { ShellProcessTask().run(listOf("su", "-c", "id -u"), 15000) }
        val granted = result.exitCode == 0 && result.stdout.trim() == "0"
        memory.record(ExecutionMode.ROOT, granted)
        PrivilegeStatus(ExecutionMode.ROOT, granted, if (granted) "root_ready" else "root_unavailable")
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { memory.record(ExecutionMode.ROOT, false); PrivilegeStatus(ExecutionMode.ROOT, false, "root_unavailable") }
}
