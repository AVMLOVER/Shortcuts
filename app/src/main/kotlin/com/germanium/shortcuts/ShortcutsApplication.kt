package com.germanium.shortcuts

import android.app.Application
import android.app.Activity
import android.os.Bundle
import java.lang.ref.WeakReference
import com.germanium.shortcuts.core.ShortcutEngine
import com.germanium.shortcuts.core.builtInActions
import com.germanium.shortcuts.core.XmlSettingsRepository
import com.germanium.shortcuts.core.XmlShortcutRepository
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

class ShortcutsApplication : Application() {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val interactions = Interactions()
    val externalReturn = com.germanium.shortcuts.core.ExternalReturnGate()
    val diagnostics by lazy { Diagnostics(this) }
    val shortcuts by lazy { XmlShortcutRepository(File(filesDir, "shortcuts")) }
    val folders by lazy { com.germanium.shortcuts.core.FolderRepository(File(filesDir, "folders")) }
    val settings by lazy { XmlSettingsRepository(File(filesDir, "settings.xml")) }
    val automations by lazy { com.germanium.shortcuts.core.AutomationRepository(File(filesDir, "automations")) }
    val workspace by lazy { com.germanium.shortcuts.core.WorkspaceFiles(File(getExternalFilesDir(null) ?: filesDir, "workspace")) }
    val runs by lazy { RunCoordinator(this) }
    val monitorRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val defaultShortcutNames=com.germanium.shortcuts.core.DefaultShortcutNames()
    @Volatile var widgetInteractionHandler: ((Interaction) -> Boolean)? = null
    @Volatile var widgetInteractionResolved: (() -> Unit)? = null
    private var widgetOverlayAllowed = false
    @Volatile private var resumedActivity: WeakReference<Activity>? = null
    fun foreground() = resumedActivity?.get()
    val actions by lazy { AndroidActions(this, interactions) { resumedActivity?.get() } }
    val engine by lazy { ShortcutEngine(builtInActions(actions), actions) }
    val privileges by lazy { PrivilegeController(this) }
    fun updateSharingTargets() { scope.launch { try { SharingTargets.refresh(this@ShortcutsApplication) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; diagnostics.trace("share_targets_failed", e) } } }
    override fun onCreate() {
        super.onCreate()
        diagnostics.install()
        updateSharingTargets()
        com.germanium.shortcuts.core.Calculator.nativeEvaluate = NativeMath::evaluate
        com.germanium.shortcuts.core.ContentNumbers.nativeStatistics = NativeMath::statistics
        com.germanium.shortcuts.core.IntegerFactors.native = NativeMath::factorsNative
        scope.launch { runs.state.map { it.runningId }.distinctUntilChanged().collect { id ->
            try { ShortcutWidgets.updateAll(this@ShortcutsApplication, id) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; diagnostics.trace("widget_state_failed", e) }
        } }
        widgetOverlayAllowed = android.provider.Settings.canDrawOverlays(this)
        interactions.onPending = { request ->
            val onDesktop = runs.state.value.fromWidget && widgetInteractionHandler?.invoke(request) == true
            if (!onDesktop && foreground() == null) showInteractionNotification(this)
        }
        interactions.onResolved = { getSystemService(android.app.NotificationManager::class.java).cancel(2002); widgetInteractionResolved?.invoke() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity); externalReturn.resume()
                val allowed = android.provider.Settings.canDrawOverlays(this@ShortcutsApplication)
                if (allowed != widgetOverlayAllowed) { widgetOverlayAllowed = allowed; scope.launch { runCatching { ShortcutWidgets.updateAll(this@ShortcutsApplication) } } }
            }
            override fun onActivityPaused(activity: Activity) { if (resumedActivity?.get() === activity) { resumedActivity = null; externalReturn.leave() } }
            override fun onActivityDestroyed(activity: Activity) { if (resumedActivity?.get() === activity) resumedActivity = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }
}
