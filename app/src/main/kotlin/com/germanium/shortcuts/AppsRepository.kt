package com.germanium.shortcuts

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(val packageName: String, val className: String, val label: String, val system: Boolean = false)
object AppsRepository {
    suspend fun load(context: Context, allPackages: Boolean = false): List<LaunchableApp> = withContext(Dispatchers.IO) {
        (if (allPackages) context.packageManager.getInstalledApplications(0).map { info ->
            LaunchableApp(info.packageName, context.packageManager.getLaunchIntentForPackage(info.packageName)?.component?.className.orEmpty(), info.loadLabel(context.packageManager).toString(), info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
        } else context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { result -> LaunchableApp(result.activityInfo.packageName, result.activityInfo.name, result.loadLabel(context.packageManager).toString(), result.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) })
            .distinctBy { it.packageName + "/" + it.className }.sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
    }
}
