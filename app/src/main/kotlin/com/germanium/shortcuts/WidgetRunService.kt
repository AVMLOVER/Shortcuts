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
            if (Settings.canDrawOverlays(this)) ContextCompat.startForegroundService(this, Intent(this, WidgetRunService::class.java).putExtra("shortcutId", shortcut).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
            else startActivity(Intent(this, WidgetPermissionActivity::class.java))
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
    private var activeRequestId: String? = null
    private var currentTheme = ThemeMode.SYSTEM
    private val interactionHandler: (Interaction) -> Boolean = { request ->
        if (!widgetInteractionSupported(request) || !Settings.canDrawOverlays(this)) false
        else { scope.launch { if (app.interactions.current.value === request && !mountOverlay(request,Value.Text(""),currentTheme)) showInteractionNotification(app) }; true }
    }
    private val interactionResolved: () -> Unit = { scope.launch { if (activeRequestId != null && app.interactions.current.value == null) clearOverlay() }; Unit }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("shortcuts.widgets", tr("widgets"), NotificationManager.IMPORTANCE_LOW))
        startForeground(2100, NotificationCompat.Builder(this, "shortcuts.widgets").setSmallIcon(R.drawable.ic_shortcuts_monochrome).setContentTitle(tr("running")).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (owned?.isActive == true && app.runs.state.value.runningId != null) return START_NOT_STICKY
        val id = intent?.getStringExtra("shortcutId") ?: run { stopSelf(); return START_NOT_STICKY }
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
        if (id !in ShortcutWidgets.selected(this,widgetId)) { stopSelf(startId); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { requireOverlayPermission(); stopSelf(startId); return START_NOT_STICKY }
        // A completed result may still be on screen; a new tap starts immediately.
        owned?.cancel(); clearOverlay()
        app.widgetInteractionHandler = interactionHandler; app.widgetInteractionResolved = interactionResolved
        owned = scope.launch {
            var run: Job? = null
            try {
                val shortcut = app.shortcuts.list().find { it.id == id } ?: throw Problem("err_no_shortcut")
                val settings = app.settings.load()
                currentTheme = settings.theme
                run = app.runs.start(shortcut, settings.mode, fromWidget = true) ?: throw Problem("err_busy")
                run.join()
                val state = app.runs.state.value
                if (state.shortcutId == id) {
                    val output=InterfacePolicies.widgetResult(state.widgetPresented,state.previewValue,state.result,state.error,state.notice) ?: return@launch
                    val shown=showOutput(output,settings.theme)
                    val attachment=withContext(Dispatchers.IO){DisplayContent.items(output).any{it is Value.Text&&AndroidContent(app).isFile(it.value)}}
                    if(shown&&attachment)awaitCancellation()
                }
                delay(7000)
            } catch (e: Exception) { if (e is CancellationException) { run?.cancel(); throw e }; app.diagnostics.trace("widget_run_failed", e); showOutput(Value.Text(app.describe(e)), ThemeMode.SYSTEM); delay(7000) }
            finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun showOutput(text: Value, theme: ThemeMode):Boolean {
        if (mountOverlay(null,text,theme)) return true
        requireOverlayPermission();return false
    }
    private fun mountOverlay(request: Interaction?, text: Value, theme: ThemeMode): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        clearOverlay()
        activeRequestId = request?.id
        val lifecycleOwner = OverlayOwner().also { owner = it }
        val view = ComposeView(this).also { overlay = it }
        view.setViewTreeLifecycleOwner(lifecycleOwner); view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { ShortcutsTheme(theme) { WidgetOverlayContent(app,request,text) { clearOverlay(); app.runs.cancel(); stopSelf() } } }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (request is Interaction.Input) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = if (request == null) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            y = if (request == null) (36 * resources.displayMetrics.density).toInt() else 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        return try { getSystemService(WindowManager::class.java).addView(view, params); true }
        catch (e: Exception) { app.diagnostics.trace("widget_overlay_failed", e); clearOverlay(); false }
    }
    private fun requireOverlayPermission() {
        val open = Intent(this,WidgetPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(open) }
        val pending = PendingIntent.getActivity(this,2101,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(2101,NotificationCompat.Builder(this,"shortcuts.widgets").setSmallIcon(R.drawable.ic_shortcuts_monochrome).setContentTitle(tr("overlay_permission")).setContentText(tr("widget_overlay_required")).setContentIntent(pending).setAutoCancel(true).build())
        app.scope.launch { runCatching { ShortcutWidgets.updateAll(app) } }
    }
    private fun clearOverlay() { activeRequestId = null; overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }; overlay = null; owner?.destroy(); owner = null }
    override fun onDestroy() {
        if (app.widgetInteractionHandler === interactionHandler) { app.widgetInteractionHandler = null; app.widgetInteractionResolved = null }
        clearOverlay(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
