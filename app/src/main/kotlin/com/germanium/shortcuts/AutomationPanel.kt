package com.germanium.shortcuts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

@Composable fun AutomationPanel(app: ShortcutsApplication, shortcuts: List<Shortcut>, report: (Throwable) -> Unit) {
    var rules by remember { mutableStateOf<List<AutomationRule>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<AutomationRule?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val monitoring by app.monitorRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val preferences = remember { app.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    suspend fun refresh() { rules = app.automations.list(); ready = true }
    fun mutate(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch { try {
            block(); refresh()
            if (rules.any { it.enabled } && !preferences.getBoolean("monitorPaused", false)) startAutomationMonitor(app)
            else if (rules.none { it.enabled }) app.stopService(Intent(app, AutomationService::class.java))
        } catch (e: Exception) { if (e is CancellationException) throw e; report(e) } finally { saving = false } }
    }
    LaunchedEffect(Unit) { try { refresh() } catch (e: Exception) { if (e is CancellationException) throw e; report(e) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t(if (monitoring) "monitor_running" else "monitor_stopped"), style = MaterialTheme.typography.titleMedium)
            Text(t("autostart_hint"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (monitoring) OutlinedButton(onClick = { preferences.edit().putBoolean("monitorPaused", true).apply(); app.stopService(Intent(app, AutomationService::class.java)) }) { Text(t("pause_monitor")) }
                else FilledTonalButton(onClick = { preferences.edit().putBoolean("monitorPaused", false).apply(); runCatching { startAutomationMonitor(app) }.onFailure(report) }, enabled = ready && rules.any { it.enabled }) { Text(t("start_monitor")) }
            }
        } }
        Button(onClick = { adding = true }, enabled = ready && shortcuts.isNotEmpty() && !saving && rules.size < 100) { Text(t("new_automation")) }
        if (rules.isEmpty()) Text(t("automation_empty"))
        rules.forEach { rule -> Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ExpressiveSwitch(rule.enabled, { enabled -> mutate { app.automations.save(rule.copy(enabled = enabled, lastMatched = false, lastToken = "")) } }, enabled = !saving)
            }
            Text(t("trigger_${rule.trigger.name.lowercase(java.util.Locale.ROOT)}") + rule.parameters.values.joinToString(prefix = if (rule.parameters.isEmpty()) "" else " · "))
            Text(shortcuts.find { it.id == rule.shortcutId }?.name ?: t("err_no_shortcut"))
            Row { TextButton(onClick = { editor = rule }, enabled = !saving) { Text(t("edit")) }; TextButton(onClick = { mutate { app.automations.delete(rule.id) } }, enabled = !saving) { Text(t("delete")) } }
        } } }
    }
    if (adding || editor != null) AutomationEditor(editor, shortcuts, onDismiss = { editor = null; adding = false }, onError = report) { rule ->
        editor = null; adding = false
        mutate { app.automations.save(rule); preferences.edit().putBoolean("monitorPaused", false).apply() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AutomationEditor(original: AutomationRule?, shortcuts: List<Shortcut>, onDismiss: () -> Unit, onError: (Throwable) -> Unit, save: (AutomationRule) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as ShortcutsApplication
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var shortcutId by rememberSaveable(original?.id) { mutableStateOf(original?.shortcutId ?: shortcuts.firstOrNull()?.id.orEmpty()) }
    var trigger by rememberSaveable(original?.id) { mutableStateOf(original?.trigger ?: TriggerKind.TIME) }
    var time by rememberSaveable(original?.id) { mutableStateOf(original?.parameters?.get("time") ?: "09:00") }
    var level by rememberSaveable(original?.id) { mutableStateOf(original?.parameters?.get("level") ?: "20") }
    var choosing by remember { mutableStateOf(false) }
    var tagId by rememberSaveable(original?.id) { mutableStateOf(original?.parameters?.get("tagId").orEmpty()) }
    var ssid by rememberSaveable(original?.id) { mutableStateOf(original?.parameters?.get("ssid").orEmpty()) }
    var wifiOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var chooseWifi by remember { mutableStateOf(false) }
    val nfcAvailable = remember { android.nfc.NfcAdapter.getDefaultAdapter(app) != null }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == android.app.Activity.RESULT_OK) tagId = result.data?.getStringExtra("tagId").orEmpty() }
    ModalBottomSheet(contentWindowInsets = { shortcutsContentInsets() }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t("new_automation"), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { if (it.length <= 200) name = it }, label = { Text(t("name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(t("choose_shortcut"), style = MaterialTheme.typography.titleMedium)
            Box { OutlinedButton(onClick = { choosing = true }) { Text(shortcuts.find { it.id == shortcutId }?.name ?: t("choose_shortcut")) }
                DropdownMenu(choosing, { choosing = false }) { shortcuts.forEach { shortcut -> DropdownMenuItem(text = { Text(shortcut.name) }, onClick = { shortcutId = shortcut.id; choosing = false }) } }
            }
            Text(t("trigger"), style = MaterialTheme.typography.titleMedium)
            TriggerKind.entries.forEach { kind -> Row(verticalAlignment = Alignment.CenterVertically) { val available = kind != TriggerKind.NFC || nfcAvailable; RadioButton(trigger == kind, { trigger = kind }, enabled = available); Text(t("trigger_${kind.name.lowercase(java.util.Locale.ROOT)}"), color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) } }
            if (trigger == TriggerKind.TIME) OutlinedTextField(time, { time = it }, label = { Text(t("time_of_day")) }, singleLine = true)
            if (trigger == TriggerKind.BATTERY_BELOW) OutlinedTextField(level, { level = it }, label = { Text(t("battery_level")) }, singleLine = true)
            if (trigger == TriggerKind.NFC) {
                Text(tagId.ifEmpty { t("nfc_scan_hint") })
                FilledTonalButton(onClick = { scanner.launch(Intent(app, NfcScanActivity::class.java)) }, enabled = nfcAvailable) { Text(t("scan_nfc")) }
            }
            if (trigger == TriggerKind.SPECIFIC_WIFI) {
                OutlinedTextField(ssid, { ssid = it }, label = { Text(t("wifi_ssid")) }, singleLine = true)
                Text(t("wifi_access_hint"), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { scope.launch { try {
                    val permissions = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (android.os.Build.VERSION.SDK_INT >= 33) permissions += android.Manifest.permission.NEARBY_WIFI_DEVICES
                    if (permissions.any { androidx.core.content.ContextCompat.checkSelfPermission(app, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) app.interactions.ask(Interaction.Permissions(permissions.toTypedArray()))
                    wifiOptions = app.actions.savedWifiNetworks(app.settings.load().mode); chooseWifi = true
                } catch (e: Exception) { if (e is CancellationException) throw e; onError(e) } } }) { Text(t("choose_wifi")) }
            }
            Button(onClick = { runCatching {
                val parameters = when (trigger) { TriggerKind.TIME -> mapOf("time" to time); TriggerKind.BATTERY_BELOW -> mapOf("level" to level); TriggerKind.NFC -> mapOf("tagId" to tagId); TriggerKind.SPECIFIC_WIFI -> mapOf("ssid" to ssid); else -> emptyMap() }
                val rule = AutomationRule(id = original?.id ?: java.util.UUID.randomUUID().toString(), name = name.ifBlank { shortcuts.find { it.id == shortcutId }?.name.orEmpty() }, shortcutId = shortcutId, trigger = trigger, parameters = parameters, enabled = original?.enabled ?: true)
                save(rule)
            }.onFailure(onError) }, enabled = shortcuts.any { it.id == shortcutId } && (trigger != TriggerKind.NFC || tagId.isNotBlank()) && (trigger != TriggerKind.SPECIFIC_WIFI || ssid.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text(t("save")) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (chooseWifi) AlertDialog(onDismissRequest = { chooseWifi = false }, title = { Text(t("choose_wifi")) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (wifiOptions.isEmpty()) Text(t("wifi_access_hint"))
            wifiOptions.forEach { name -> TextButton(onClick = { ssid = name; chooseWifi = false }) { Text(name) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseWifi = false }) { Text(t("close")) } })
}
