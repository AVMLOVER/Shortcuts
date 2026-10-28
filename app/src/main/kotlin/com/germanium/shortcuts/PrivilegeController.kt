package com.germanium.shortcuts
import android.content.pm.PackageManager
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

data class PrivilegeStatus(val mode: ExecutionMode, val available: Boolean, val message: String)
class PrivilegeController {
    fun shizukuStatus(): PrivilegeStatus = runCatching {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> PrivilegeStatus(ExecutionMode.SHIZUKU, false, "shizuku_unavailable")
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> PrivilegeStatus(ExecutionMode.SHIZUKU, false, "shizuku_permission")
            else -> PrivilegeStatus(ExecutionMode.SHIZUKU, true, "shizuku_ready")
        }
    }.getOrDefault(PrivilegeStatus(ExecutionMode.SHIZUKU, false, "shizuku_unavailable"))
    fun requestShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) throw Problem("shizuku_unavailable")
        if (Shizuku.shouldShowRequestPermissionRationale()) throw Problem("shizuku_permission")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(2048)
    }
    suspend fun requestRoot(): PrivilegeStatus = try {
        val result = runInterruptible(Dispatchers.IO) { ShellProcessTask().run(listOf("su", "-c", "id -u"), 15000) }
        val granted = result.exitCode == 0 && result.stdout.trim() == "0"
        PrivilegeStatus(ExecutionMode.ROOT, granted, if (granted) "root_ready" else "root_unavailable")
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { PrivilegeStatus(ExecutionMode.ROOT, false, "root_unavailable") }
}
