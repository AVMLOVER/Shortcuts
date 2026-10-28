package com.germanium.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

internal object ShortcutWidgets {
    val providers = linkedMapOf(WidgetOne::class.java to 1, WidgetTwo::class.java to 2, WidgetFour::class.java to 4, WidgetEight::class.java to 8)
    private fun preferences(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun selected(context: Context, id: Int) = preferences(context).getString("widget$id", "").orEmpty().split(',').filter { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }
    fun save(context: Context, id: Int, shortcuts: List<String>) { preferences(context).edit().putString("widget$id", shortcuts.distinct().take(8).joinToString(",")).apply() }
    fun remove(context: Context, id: Int) { preferences(context).edit().remove("widget$id").apply() }
    suspend fun updateAll(app: ShortcutsApplication, runningId: String? = app.runs.state.value.runningId) = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(app)
        val ids = providers.keys.flatMap { manager.getAppWidgetIds(ComponentName(app, it)).toList() }
        if (ids.isEmpty()) return@withContext
        val shortcuts = app.shortcuts.list().associateBy { it.id }
        ids.forEach { id -> render(app, manager, id, shortcuts, runningId) }
    }
    fun render(context: Context, manager: AppWidgetManager, id: Int, shortcuts: Map<String, Shortcut>, runningId: String?) {
        val info = manager.getAppWidgetInfo(id) ?: return
        val capacity = providers.entries.find { it.key.name == info.provider.className }?.value ?: return
        val chosen = selected(context, id).take(capacity).mapNotNull(shortcuts::get)
        val root = RemoteViews(context.packageName, R.layout.widget_root)
        root.removeAllViews(R.id.widget_root)
        val columns = if (capacity == 8) 2 else capacity
        for (group in chosen.chunked(columns)) {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            for (shortcut in group) {
                val item = RemoteViews(context.packageName, R.layout.widget_item)
                item.setImageViewBitmap(R.id.widget_icon, icon(context, shortcut))
                item.setTextViewText(R.id.widget_title, shortcut.name)
                item.setViewVisibility(R.id.widget_title, if (capacity == 1) View.GONE else View.VISIBLE)
                val running = runningId == shortcut.id
                item.setViewVisibility(R.id.widget_progress, if (running) View.VISIBLE else View.GONE)
                item.setViewVisibility(R.id.widget_idle_ring, if (running) View.GONE else View.VISIBLE)
                item.setContentDescription(R.id.widget_item, shortcut.name)
                val launch = Intent(context, WidgetRunActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("shortcutId", shortcut.id)
                    .setData(android.net.Uri.parse("shortcuts://widget/$id/${shortcut.id}"))
                item.setOnClickPendingIntent(R.id.widget_item, PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                row.addView(R.id.widget_row, item)
            }
            root.addView(R.id.widget_root, row)
        }
        manager.updateAppWidget(id, root)
    }
    private fun icon(context: Context, shortcut: Shortcut): Bitmap {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        val bg = runCatching { if (shortcut.color.startsWith('#')) Color.parseColor(shortcut.color) else ContextCompat.getColor(context, R.color.widget_accent) }.getOrDefault(Color.GRAY)
        canvas.drawRoundRect(4f, 4f, 140f, 140f, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val drawable = ContextCompat.getDrawable(context, ShortcutStyles.icons[shortcut.icon] ?: R.drawable.ic_glyph_layers)?.mutate()
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(bg)
        drawable?.setTint(if (luminance > 0.5) Color.BLACK else Color.WHITE); drawable?.setBounds(36, 36, 108, 108); drawable?.draw(canvas)
        return bitmap
    }
}

abstract class ShortcutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync(); val app = context.applicationContext as ShortcutsApplication
        app.scope.launch { try { ShortcutWidgets.updateAll(app) } catch (e: Exception) { if (e is CancellationException) throw e; app.diagnostics.trace("widget_update_failed", e) } finally { pending.finish() } }
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { ShortcutWidgets.remove(context, it) } }
}
class WidgetOne : ShortcutWidgetProvider()
class WidgetTwo : ShortcutWidgetProvider()
class WidgetFour : ShortcutWidgetProvider()
class WidgetEight : ShortcutWidgetProvider()

class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val info = manager.getAppWidgetInfo(id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider?.packageName != packageName) { finish(); return }
        val capacity = ShortcutWidgets.providers.entries.find { it.key.name == info.provider.className }?.value ?: run { finish(); return }
        val app = application as ShortcutsApplication
        setContent {
            var items by remember { mutableStateOf<List<Shortcut>>(emptyList()) }
            var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ShortcutWidgets.selected(app, id)) }
            var theme by remember { mutableStateOf(ThemeMode.SYSTEM) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { items = app.shortcuts.list(); theme = app.settings.load().theme }
            ShortcutsTheme(theme) { Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(t("widget_select"), style = MaterialTheme.typography.headlineMedium)
                Text("${selected.size}/$capacity", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(items, key = { it.id }) { shortcut ->
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ShortcutBadge(shortcut.icon, shortcut.color, 40.dp); Text(shortcut.name, Modifier.weight(1f))
                            ExpressiveSwitch(shortcut.id in selected, { enabled -> selected = if (enabled) (selected + shortcut.id).distinct().take(capacity) else selected - shortcut.id }, enabled = shortcut.id in selected || selected.size < capacity)
                        }
                    }
                } }
                if (!android.provider.Settings.canDrawOverlays(this@WidgetConfigureActivity)) FilledTonalButton(onClick = { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }) { Text(t("overlay_permission")) }
                Button(onClick = { scope.launch {
                    val valid = selected.filter { chosen -> items.any { it.id == chosen } }.take(capacity)
                    if (valid.isEmpty()) return@launch
                    ShortcutWidgets.save(app, id, valid); ShortcutWidgets.updateAll(app)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                } }, enabled = selected.any { chosen -> items.any { it.id == chosen } }, modifier = Modifier.fillMaxWidth()) { Text(t("done")) }
            } } }
        }
    }
}
