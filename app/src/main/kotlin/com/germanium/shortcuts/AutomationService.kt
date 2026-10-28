package com.germanium.shortcuts

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

class AutomationService : Service() {
    private val app get() = application as ShortcutsApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Signal(val event: String, val battery: Int? = null, val charging: Boolean? = null, val wifi: Boolean? = null, val connected: Boolean? = null)
    private val signals = Channel<Signal>(64)
    private val queue = Channel<String>(16)
    private var snapshot = DeviceSnapshot()
    private var rules = emptyList<AutomationRule>()
    private var ownedRun: Job? = null
    private var activeRule: String? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    signals.trySend(Signal("battery", (level * 100 / scale).coerceIn(0, 100), intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0))
                }
                Intent.ACTION_POWER_CONNECTED -> signals.trySend(Signal("charging", charging = true))
                Intent.ACTION_POWER_DISCONNECTED -> signals.trySend(Signal("charging", charging = false))
                Intent.ACTION_SCREEN_ON -> signals.trySend(Signal("screen_on"))
                Intent.ACTION_SCREEN_OFF -> signals.trySend(Signal("screen_off"))
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> signals.trySend(Signal("tick"))
            }
        }
    }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateNetwork(network) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { signals.trySend(Signal("network", wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), connected = true)) }
        override fun onLost(network: Network) { updateNetwork(connectivity.activeNetwork) }
        private fun updateNetwork(network: Network?) { val capabilities = network?.let(connectivity::getNetworkCapabilities); signals.trySend(Signal("network", wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, connected = capabilities != null)) }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("shortcuts.automation", tr("automation_monitor"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2001, Intent(this, AutomationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(2001, NotificationCompat.Builder(this, "shortcuts.automation").setSmallIcon(R.drawable.ic_automation).setContentTitle(tr("automation_monitor"))
            .setContentText(tr("monitor_body")).setContentIntent(open).setOngoing(true).addAction(0, tr("pause_monitor"), stop).build())
        app.monitorRunning.value = true
        val initialBattery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val initialLevel = initialBattery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val initialScale = initialBattery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val initialNetwork = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        snapshot = DeviceSnapshot(initialLevel * 100 / initialScale, (initialBattery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0, initialNetwork?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, initialNetwork != null)
        scope.launch {
            for (signal in signals) try {
                snapshot = snapshot.copy(battery = signal.battery ?: snapshot.battery, charging = signal.charging ?: snapshot.charging, wifi = signal.wifi ?: snapshot.wifi, connected = signal.connected ?: snapshot.connected, ssid = AndroidWifi.current(app))
                if (signal.event == "reload") {
                    rules = app.automations.list()
                    if (activeRule != null && rules.none { it.id == activeRule && it.enabled }) ownedRun?.cancel()
                    if (rules.none { it.enabled }) { stopSelf(); continue }
                }
                val event = if (signal.event == "reload") "tick" else signal.event
                for (rule in rules.toList()) {
                    val decision = evaluateTrigger(rule, snapshot, event, System.currentTimeMillis())
                    var accepted = true
                    if (decision.fire) { accepted = queue.trySend(rule.id).isSuccess; app.diagnostics.trace("automation_trigger id=${rule.id} trigger=${rule.trigger} queued=$accepted") }
                    if (accepted && decision.rule != rule) { if (app.automations.saveState(rule, decision.rule)) rules = rules.map { if (it.id == rule.id) decision.rule else it } else rules = app.automations.list() }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; app.diagnostics.trace("automation_monitor_failed", e); if (signal.event == "reload") { rules = emptyList(); stopSelf() } }
        }
        scope.launch {
            for (id in queue) try {
                if (app.automations.list().none { it.id == id && it.enabled }) continue

                while (isActive) {
                    app.runs.state.first { it.runningId == null }
                    val latest = app.automations.list().find { it.id == id && it.enabled } ?: break
                    val shortcut = app.shortcuts.list().find { it.id == latest.shortcutId } ?: throw Problem("err_no_shortcut")
                    val run = app.runs.start(shortcut, app.settings.load().mode)
                    if (run != null) { activeRule = id; ownedRun = run; run.join(); activeRule = null; ownedRun = null; break }
                    delay(100)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; app.diagnostics.trace("automation_execution_failed", e) }
        }
        scope.launch { while (isActive) { signals.send(Signal("tick")); delay(15000) } }
        signals.trySend(Signal("reload"))
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        connectivity.registerDefaultNetworkCallback(networkCallback)
        app.diagnostics.trace("automation_monitor_started")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { getSharedPreferences("permissions", MODE_PRIVATE).edit().putBoolean("monitorPaused", true).apply(); ownedRun?.cancel(); stopSelf(); return START_NOT_STICKY }
        signals.trySend(Signal("reload"))
        if (intent?.getBooleanExtra("boot", false) == true) signals.trySend(Signal("boot"))
        intent?.getStringExtra("nfcTag")?.takeIf { it.matches(Regex("[0-9A-Fa-f]{2,64}")) }?.let { signals.trySend(Signal("nfc:$it")) }
        return START_STICKY
    }
    override fun onDestroy() { if (registered) unregisterReceiver(receiver); runCatching { connectivity.unregisterNetworkCallback(networkCallback) }; ownedRun?.cancel(); scope.cancel(); app.monitorRunning.value = false; app.diagnostics.trace("automation_monitor_stopped"); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
    companion object { const val STOP = "com.germanium.shortcuts.STOP_MONITOR" }
}

fun startAutomationMonitor(context: Context, boot: Boolean = false, nfcTag: String? = null) {
    ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java).putExtra("boot", boot).putExtra("nfcTag", nfcTag))
}
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync(); val app = context.applicationContext as ShortcutsApplication
        app.scope.launch {
            try { if (!app.getSharedPreferences("permissions", Context.MODE_PRIVATE).getBoolean("monitorPaused", false) && app.automations.list().any { it.enabled }) startAutomationMonitor(app, intent.action == Intent.ACTION_BOOT_COMPLETED) }
            catch (e: Exception) { app.diagnostics.trace("automation_boot_failed", e) } finally { pending.finish() }
        }
    }
}
