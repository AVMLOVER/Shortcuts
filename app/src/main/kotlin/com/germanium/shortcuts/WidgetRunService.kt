package com.germanium.shortcuts

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

class WidgetRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val shortcut = intent.getStringExtra("shortcutId") ?: return
            if (shortcut !in ShortcutWidgets.selected(this, id)) return
            if (Settings.canDrawOverlays(this)) ContextCompat.startForegroundService(this, Intent(this, WidgetRunService::class.java).putExtra("shortcutId", shortcut))
            else startActivity(Intent(this, TileRunActivity::class.java).putExtra("shortcutId", shortcut))
        } finally { finish() }
    }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    init { controller.performAttach(); controller.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

class WidgetRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as ShortcutsApplication
    private var owned: Job? = null
    private var overlay: ComposeView? = null
    private var owner: OverlayOwner? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("shortcuts.widgets", tr("widgets"), NotificationManager.IMPORTANCE_LOW))
        startForeground(2100, NotificationCompat.Builder(this, "shortcuts.widgets").setSmallIcon(R.drawable.ic_shortcuts_monochrome).setContentTitle(tr("running")).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (owned?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("shortcutId") ?: run { stopSelf(); return START_NOT_STICKY }
        owned = scope.launch {
            var run: Job? = null
            try {
                val shortcut = app.shortcuts.list().find { it.id == id } ?: throw Problem("err_no_shortcut")
                val settings = app.settings.load()
                run = app.runs.start(shortcut, settings.mode, fromWidget = true) ?: throw Problem("err_busy")
                run.join()
                val state = app.runs.state.value
                if (state.shortcutId == id) showOutput(state.error ?: state.previewOutput ?: state.result?.output.orEmpty(), settings.theme)
                delay(7000)
            } catch (e: Exception) { if (e is CancellationException) { run?.cancel(); throw e }; app.diagnostics.trace("widget_run_failed", e); showOutput(app.describe(e), ThemeMode.SYSTEM); delay(7000) }
            finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun showOutput(text: String, theme: ThemeMode) {
        if (!Settings.canDrawOverlays(this)) return
        clearOverlay()
        val lifecycleOwner = OverlayOwner().also { owner = it }
        val view = ComposeView(this).also { overlay = it }
        view.setViewTreeLifecycleOwner(lifecycleOwner); view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { ShortcutsTheme(theme) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 12.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(text.ifEmpty { t("no_output") }.take(4096), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 10)
                    IconButton(onClick = { clearOverlay(); stopSelf() }) { AppIcon(R.drawable.ic_close, description = t("close")) }
                }
            }
        } }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * resources.displayMetrics.density).toInt()
        }
        try { getSystemService(WindowManager::class.java).addView(view, params) }
        catch (e: Exception) { app.diagnostics.trace("widget_overlay_failed", e); clearOverlay() }
    }
    private fun clearOverlay() { overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }; overlay = null; owner?.destroy(); owner = null }
    override fun onDestroy() { clearOverlay(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
