package com.germanium.shortcuts

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

internal object ShortcutTiles {
    val services = listOf(ShortcutTile1::class.java, ShortcutTile2::class.java, ShortcutTile3::class.java, ShortcutTile4::class.java)
    private fun preferences(context: Context) = context.getSharedPreferences("control_tiles", Context.MODE_PRIVATE)
    fun shortcutId(context: Context, slot: Int): String? = if (slot in services.indices) preferences(context).getString("slot$slot", null) else null
    fun assign(context: Context, slot: Int, id: String) {
        require(slot in services.indices)
        preferences(context).edit().putString("slot$slot", id).apply()
        TileService.requestListeningState(context, ComponentName(context, services[slot]))
    }
    fun remove(context: Context, id: String) {
        services.indices.filter { shortcutId(context, it) == id }.forEach { slot ->
            preferences(context).edit().remove("slot$slot").apply()
            TileService.requestListeningState(context, ComponentName(context, services[slot]))
        }
    }
    fun add(activity: Activity, shortcut: Shortcut, slot: Int, notice: (String) -> Unit, report: (Throwable) -> Unit) {
        try {
            assign(activity, slot, shortcut.id)
            if (Build.VERSION.SDK_INT >= 33) activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(activity, services[slot]), shortcut.name, Icon.createWithResource(activity, ShortcutStyles.icons[shortcut.icon] ?: R.drawable.ic_shortcuts_monochrome), activity.mainExecutor
            ) { result -> notice(when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "tile_added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "tile_not_added"
                else -> "tile_manual"
            }) } else notice("tile_manual")
        } catch (e: Exception) { report(e) }
    }
}

@Composable internal fun TileSlotPicker(app: ShortcutsApplication, shortcut: Shortcut, dismiss: () -> Unit, select: (Int) -> Unit) {
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { titles = app.shortcuts.list().associate { it.id to it.name } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(t("add_to_controls")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("tile_slots_detail"))
            ShortcutTiles.services.indices.forEach { slot -> FilledTonalButton(onClick = { select(slot) }, modifier = Modifier.fillMaxWidth().testTag("tile_slot_$slot")) {
                Text(t("tile_slot", slot + 1) + " · " + (titles[ShortcutTiles.shortcutId(app, slot)] ?: t("tile_empty")))
            } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(t("cancel")) } })
}

abstract class ShortcutTileService(private val slot: Int) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onStartListening() {
        super.onStartListening()
        scope.launch { try {
            val app = application as ShortcutsApplication
            val shortcut = app.shortcuts.list().find { it.id == ShortcutTiles.shortcutId(app, slot) }
            qsTile?.apply {
                label = shortcut?.name ?: app.tr("tile_slot", slot + 1)
                icon = Icon.createWithResource(app, ShortcutStyles.icons[shortcut?.icon] ?: R.drawable.ic_shortcuts_monochrome)
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) subtitle = if (shortcut == null) app.tr("tile_empty") else app.tr("run")
                contentDescription = label; updateTile()
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; (application as ShortcutsApplication).diagnostics.trace("tile_refresh_failed", e) } }
    }
    override fun onClick() {
        super.onClick()
        val open = {
            val intent = Intent(this, TileRunActivity::class.java).putExtra("slot", slot).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else collapseLegacy(intent)
        }
        if (isLocked) unlockAndRun { open() } else open()
    }
    // API 26-33 have only this overload; the PendingIntent overload is guarded above.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun collapseLegacy(intent: Intent) { startActivityAndCollapse(intent) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class ShortcutTile1 : ShortcutTileService(0)
class ShortcutTile2 : ShortcutTileService(1)
class ShortcutTile3 : ShortcutTileService(2)
class ShortcutTile4 : ShortcutTileService(3)

class TileRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableShortcutsEdgeToEdge()
        val app = application as ShortcutsApplication
        val slot = intent.getIntExtra("slot", -1)
        setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            var shortcut by remember { mutableStateOf<Shortcut?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(true) }
            val run by app.runs.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                try {
                    settings = app.settings.load()
                    shortcut = app.shortcuts.list().find { it.id == (intent.getStringExtra("shortcutId") ?: ShortcutTiles.shortcutId(app, slot)) }
                    if (shortcut == null) error = app.tr("tile_empty")
                    else if (savedInstanceState == null && app.runs.start(shortcut!!, settings.mode, preview = true) == null) error = app.tr("err_busy")
                } catch (e: Exception) { if (e is CancellationException) throw e; error = app.describe(e) }
                finally { loading = false }
            }
            ShortcutsTheme(settings.theme) {
                Scaffold(contentWindowInsets = shortcutsContentInsets()) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(shortcut?.name ?: t("app_name"), style = MaterialTheme.typography.headlineMedium)
                    if (loading || run.runningId == shortcut?.id && run.runningId != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(t("running")) }
                    (error ?: run.error.takeIf { run.shortcutId == shortcut?.id })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (run.shortcutId == shortcut?.id) run.result?.let { ResultCard(it) }
                    if (run.runningId != null && run.runningId == shortcut?.id) TextButton(onClick = { app.runs.cancel() }) { Text(t("stop")) }
                    Button(onClick = { finish() }) { Text(t("close")) }
                } }
                InteractionHost(app)
            }
        }
    }
}
