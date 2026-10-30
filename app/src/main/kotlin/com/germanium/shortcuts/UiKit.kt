package com.germanium.shortcuts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun ShortcutsTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    SideEffect { (context as? ComponentActivity)?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    val colors = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme(primary = Color(0xFFD0BCFF)) else lightColorScheme(primary = Color(0xFF6750A4), primaryContainer = Color(0xFFEADDFF))
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}
@Composable fun AppIcon(resource: Int, modifier: Modifier = Modifier, description: String? = null) { Icon(painterResource(resource), description, modifier.size(24.dp)) }
fun modeKey(mode: ExecutionMode) = when (mode) { ExecutionMode.STANDARD -> "standard"; ExecutionMode.ROOT -> "root"; ExecutionMode.SHIZUKU -> "shizuku" }

@Composable fun ObserveLifecycle(model: ShortcutsViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val received = Shizuku.OnBinderReceivedListener { model.refreshPrivileges() }
        val dead = Shizuku.OnBinderDeadListener { model.refreshPrivileges() }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> model.refreshPrivileges() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.reload() }
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission); lifecycle.addObserver(observer)
        onDispose { Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead); Shizuku.removeRequestPermissionResultListener(permission); lifecycle.removeObserver(observer) }
    }
}
@Composable fun InteractionHost(app: ShortcutsApplication) {
    val request by app.interactions.current.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var permissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchId by rememberSaveable { mutableStateOf<String?>(null) }
    var captureId by rememberSaveable { mutableStateOf<String?>(null) }
    var fileId by rememberSaveable { mutableStateOf<String?>(null) }
    var photoId by rememberSaveable { mutableStateOf<String?>(null) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var savePdfId by rememberSaveable { mutableStateOf<String?>(null) }
    val savePdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val active = app.interactions.current.value?.takeIf { it.id == savePdfId } as? Interaction.SavePdf
        if (active != null) {
            if (uri != null) runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            app.interactions.reply(active, uri?.toString())
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val active = app.interactions.current.value?.takeIf { it.id == fileId } as? Interaction.PickFile
        if (active != null) {
            if (uri != null) runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.onFailure { app.diagnostics.trace("file_permission_not_persistent", it) }
            app.interactions.reply(active, uri?.toString())
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val active = app.interactions.current.value?.takeIf { it.id == photoId } as? Interaction.TakePhoto
        if (active != null) app.interactions.reply(active, photoUri.takeIf { success })
        if (!success) photoUri?.let { runCatching { app.contentResolver.delete(Uri.parse(it), null, null) } }
        photoUri = null
    }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val active = app.interactions.current.value as? Interaction.Capture
        if (active != null && active.id == captureId) {
            if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) app.interactions.reply(active, null)
            else try { ContextCompat.startForegroundService(app, Intent(app, ScreenshotService::class.java).putExtra("request", active.id).putExtra("data", result.data)) }
            catch (e: Exception) { app.diagnostics.trace("capture_start_failed", e); active.result.completeExceptionally(Problem("err_capture")) }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val active = app.interactions.current.value?.takeIf { it.id == permissionId }
        if (active != null) scope.launch { lifecycle.withResumed {
            val granted = (active as? Interaction.Permissions)?.permissions?.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED } == true
            app.diagnostics.trace("permission_result granted=$granted")
            app.interactions.reply(active, if (granted) "ok" else null)
        } }
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val active = app.interactions.current.value?.takeIf { it.id == launchId }
        if (active != null) scope.launch { lifecycle.withResumed { app.interactions.reply(active, result.resultCode.toString()) } }
    }
    LaunchedEffect(request?.id) {
        val active = request ?: return@LaunchedEffect
        if (!active.launched && (active is Interaction.Permissions || active is Interaction.Launch || active is Interaction.Foreground || active is Interaction.Capture || active is Interaction.PickFile || active is Interaction.TakePhoto || active is Interaction.SavePdf)) {
            lifecycle.withResumed {
                if (app.interactions.current.value !== active || active.result.isCompleted) return@withResumed
                active.launched = true
                try {
                    when (active) {
                        is Interaction.Permissions -> { permissionId = active.id; permissionLauncher.launch(active.permissions) }
                        is Interaction.Launch -> { launchId = active.id; activityLauncher.launch(active.intent) }
                        is Interaction.Foreground -> app.interactions.reply(active, "ok")
                        is Interaction.Capture -> {
                            captureId = active.id
                            val projection = app.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                            val intent = if (Build.VERSION.SDK_INT >= 34) projection.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else projection.createScreenCaptureIntent()
                            captureLauncher.launch(intent)
                        }
                        is Interaction.PickFile -> { fileId = active.id; fileLauncher.launch(arrayOf(if (active.imageOnly) "image/*" else "*/*")) }
                        is Interaction.SavePdf -> { savePdfId = active.id; savePdfLauncher.launch(active.filename) }
                        is Interaction.TakePhoto -> {
                            photoId = active.id
                            val folder = java.io.File(app.cacheDir, "camera").also { check(it.isDirectory || it.mkdirs()) }
                            val file = java.io.File(folder, "photo-${java.util.UUID.randomUUID()}.jpg")
                            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                            photoUri = uri.toString(); photoLauncher.launch(uri)
                        }
                        else -> Unit
                    }
                } catch (e: Exception) { app.diagnostics.trace("system_ui_failed", e); active.result.completeExceptionally(Problem("err_no_handler")) }
            }
        }
    }
    when (val active = request.takeIf { lifecycleState.isAtLeast(Lifecycle.State.RESUMED) }) {
        is Interaction.ImageEdit -> ImageEditDialog(app, active)
        is Interaction.ResultDialog -> AlertDialog(onDismissRequest = { if (active.allowCancel) app.interactions.reply(active, null) }, title = { Text(active.title) }, text = { QuickLookContent(app, active.content) },
            confirmButton = { TextButton(onClick = { app.interactions.reply(active, "ok") }, modifier = Modifier.testTag("confirm_result")) { Text(t("confirm")) } },
            dismissButton = if (active.allowCancel) ({ TextButton(onClick = { app.interactions.reply(active, null) }, modifier = Modifier.testTag("cancel_result")) { Text(t("cancel")) } }) else null)
        is Interaction.Html -> HtmlPreviewDialog(active.html) { app.interactions.reply(active, "ok") }
        is Interaction.Input -> {
            var answer by rememberSaveable(active.id) { mutableStateOf(active.initial) }
            val valid = answer.length <= 262144 && when (active.type) { "number" -> runCatching { parseNumber(answer) }.isSuccess; "date" -> runCatching { parseEventTime(answer) }.isSuccess; else -> true }
            AlertDialog(onDismissRequest = { app.interactions.reply(active, null) }, title = { Text(active.title) }, text = {
                OutlinedTextField(answer, { if (it.length <= 262144) answer = it }, placeholder = { Text(active.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (active.type == "number") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().testTag("requested_input"), isError = !valid, supportingText = if (active.type == "date") ({ Text(t("date_hint")) }) else null)
            }, confirmButton = { TextButton(onClick = { app.interactions.reply(active, answer) }, enabled = valid) { Text(t("done")) } }, dismissButton = { TextButton(onClick = { app.interactions.reply(active, null) }) { Text(t("cancel")) } })
        }
        is Interaction.Checklist -> {
            var selected by rememberSaveable(active.id) { mutableStateOf(listOf<String>()) }
            AlertDialog(onDismissRequest = { app.interactions.reply(active, null) }, title = { Text(active.title) }, text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { active.items.forEach { (id, label) ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }); Text(label) }
                } }
            }, confirmButton = { TextButton(onClick = { app.interactions.reply(active, active.items.map { it.first }.filter { it in selected }.joinToString(",")) }) { Text(t("done")) } }, dismissButton = { TextButton(onClick = { app.interactions.reply(active, null) }) { Text(t("cancel")) } })
        }
        is Interaction.Menu -> AlertDialog(onDismissRequest = { app.interactions.reply(active, null) }, title = { Text(active.title) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                active.items.forEach { (id, label) -> FilledTonalButton(onClick = { app.interactions.reply(active, id) }, modifier = Modifier.fillMaxWidth().testTag("menu_choice_$id")) { Text(label) } }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { app.interactions.reply(active, null) }) { Text(t("cancel")) } })
        is Interaction.QuickLook -> AlertDialog(modifier = Modifier.testTag("quick_look"), onDismissRequest = { app.interactions.reply(active, "ok") }, title = { Text(t("quick_look")) }, text = {
            QuickLookContent(app, active.value)
        }, confirmButton = { TextButton(onClick = { app.interactions.reply(active, "ok") }, modifier = Modifier.testTag("close_quick_look")) { Text(t("close")) } }, dismissButton = {
            TextButton(onClick = { runCatching { app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Shortcuts", active.text)) }.onFailure { app.diagnostics.trace("clipboard_failed", it) } }) { Text(t("copy_result")) }
        })
        else -> Unit
    }
}
object StartupPermissions {
    fun start(app: ShortcutsApplication) {
        val preferences = app.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
        app.scope.launch {
            runCatching {
                // The first runtime permission cannot depend on an already granted notification permission.
                while (app.foreground() == null) kotlinx.coroutines.delay(50)
                if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("notificationAsked", false)) {
                    preferences.edit().putBoolean("notificationAsked", true).apply()
                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) app.interactions.ask(Interaction.Permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
                }
                if (!preferences.getBoolean("batteryAsked", false)) {
                    preferences.edit().putBoolean("batteryAsked", true).apply()
                    if (!app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) app.interactions.ask(Interaction.Launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))))
                }
                if (!preferences.getBoolean("overlayAsked", false)) {
                    preferences.edit().putBoolean("overlayAsked", true).apply()
                    if (!Settings.canDrawOverlays(app)) app.interactions.ask(Interaction.Launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))))
                }
            }.onFailure { app.diagnostics.trace("startup_permissions_failed", it) }
        }
    }
}
@Composable fun ErrorCard(message: String, dismiss: () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(message); TextButton(onClick = dismiss) { Text(t("close")) } } } }
@Composable fun ResultCard(result: RunResult) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(t(if (result.status == RunStatus.SUCCEEDED) "run_success" else "run_failed"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); ShareValueButton(result.outputValue) }
        val failure=result.failure
        if (failure != null) Text(context.describe(failure)) else QuickLookContent(context.applicationContext as ShortcutsApplication, result.outputValue)
        Text(t("actions_count", result.executedCount), style = MaterialTheme.typography.labelMedium)
        if (result.historyTruncated) Text(t("history_limited"), style = MaterialTheme.typography.bodySmall)
        result.variables.forEach { (name, value) -> Text("$name = ${value.text().take(512)}", style = MaterialTheme.typography.bodySmall) }
    } }
}
