package com.germanium.shortcuts

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object SharingTargets {
    const val CATEGORY = "com.germanium.shortcuts.SHARE"
    private val mutex = Mutex()
    private var signature: String? = null
    suspend fun refresh(app: ShortcutsApplication) = mutex.withLock { withContext(Dispatchers.IO) {
        val eligible = app.shortcuts.list().filter { ContentInput.shareActions(it).isNotEmpty() }.sortedByDescending { it.updatedAt }
        val component = ComponentName(app, "${app.packageName}.ShareEntry")
        val enabled = app.packageManager.getComponentEnabledSetting(component)
        val desired = if (eligible.isNotEmpty()) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (enabled != desired) app.packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        val fingerprint = eligible.joinToString("|") { "${it.id}:${it.name}:${it.icon}:${it.updatedAt}" }
        if (signature == fingerprint) return@withContext
        val current = ShortcutManagerCompat.getDynamicShortcuts(app)
        val unrelated = current.filterNot { it.id.startsWith("share-") }
        val capacity = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(app) - unrelated.size).coerceAtLeast(0)
        val shortcuts = eligible.take(capacity).mapIndexed { index, shortcut -> ShortcutInfoCompat.Builder(app, "share-${shortcut.id}")
            .setShortLabel(shortcut.name).setLongLabel(shortcut.name).setRank(index).setLongLived(true)
            .setIcon(IconCompat.createWithResource(app, ShortcutStyles.icons[shortcut.icon] ?: R.drawable.ic_shortcuts_monochrome))
            .setCategories(setOf(CATEGORY)).setIntent(Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND).putExtra("shortcutId", shortcut.id)).build() }
        val stale = current.filter { it.id.startsWith("share-") && it.id !in shortcuts.map { s -> s.id } }.map { it.id }
        if (stale.isNotEmpty()) { ShortcutManagerCompat.disableShortcuts(app, stale, app.tr("share_shortcut_unavailable")); ShortcutManagerCompat.removeLongLivedShortcuts(app, stale) }
        ShortcutManagerCompat.enableShortcuts(app, shortcuts)
        if (ShortcutManagerCompat.setDynamicShortcuts(app, unrelated + shortcuts)) signature = fingerprint
    } }
}
