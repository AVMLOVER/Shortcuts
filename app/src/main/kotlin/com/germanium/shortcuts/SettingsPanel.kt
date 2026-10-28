package com.germanium.shortcuts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsPanel(app: ShortcutsApplication, model: ShortcutsViewModel, state: HomeState, transfers: TransferTools,
    launch: (Interaction) -> Unit, viewLogs: () -> Unit) {
    val enabled = state.storageReady && !state.saving
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("appearance", "appearance_settings", R.drawable.ic_glyph_star, initiallyOpen = true) {
            Text(t("theme"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeMode.entries.forEachIndexed { index, theme -> SegmentedButton(state.settings.theme == theme, { model.selectTheme(theme) }, shape = SegmentedButtonDefaults.itemShape(index, 3), enabled = enabled,
                    modifier = Modifier.weight(1f).height(64.dp).testTag("theme_${theme.name}")) { Text(t(when (theme) { ThemeMode.SYSTEM -> "theme_system"; ThemeMode.LIGHT -> "theme_light"; ThemeMode.DARK -> "theme_dark" }), minLines = 2, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
            }
            if (Build.VERSION.SDK_INT >= 33) SettingsRow("locale_settings", R.drawable.ic_glyph_globe, click = { launch(Interaction.Launch(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${app.packageName}")))) })
        }
        SettingsGroup("execution", "execution_mode", R.drawable.ic_glyph_code) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                ExecutionMode.entries.forEachIndexed { index, mode -> SegmentedButton(state.settings.mode == mode, { model.selectMode(mode) }, shape = SegmentedButtonDefaults.itemShape(index, 3), enabled = enabled && state.runningId == null) { Text(t("execution_${mode.name.lowercase(java.util.Locale.ROOT)}"), maxLines = 1) } }
            }
            Text(t(state.privilege.message), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (state.settings.mode != ExecutionMode.STANDARD) SettingsRow(if (state.authorizing) "authorizing" else "authorize", R.drawable.ic_glyph_check, enabled = !state.authorizing, click = model::authorize)
        }
        SettingsGroup("calculation", "calculation_settings", R.drawable.ic_glyph_code) {
            Text(t("angle_unit"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                AngleUnit.entries.forEachIndexed { index, unit -> SegmentedButton(state.settings.angleUnit == unit, { model.selectAngleUnit(unit) }, shape = SegmentedButtonDefaults.itemShape(index, 3), enabled = enabled,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("angle_${unit.name}")) { Text(t("angle_${unit.name.lowercase(java.util.Locale.ROOT)}"), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) } }
            }
            Text(t("angle_unit_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        SettingsGroup("permissions", "permissions", R.drawable.ic_glyph_check) {
            SettingsRow("notification_permission", R.drawable.ic_glyph_chat, status = t(if (state.notificationsEnabled) "allowed" else "not_allowed"), click = {
                if (Build.VERSION.SDK_INT >= 33) launch(Interaction.Permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))) else launch(Interaction.Launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)))
            })
            SettingsRow("battery_permission", R.drawable.ic_glyph_bolt, status = t(if (state.batteryExempt) "allowed" else "not_allowed"), click = { launch(Interaction.Launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}")))) })
            SettingsRow("autostart", R.drawable.ic_automation, detail = t("autostart_hint"), click = { launch(Interaction.Launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))) })
            SettingsRow("overlay_permission", R.drawable.ic_controls, status = t(if (state.overlayEnabled) "allowed" else "not_allowed"), click = { launch(Interaction.Launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}")))) })
            SettingsRow("camera_permission", R.drawable.ic_glyph_camera, status = t(if (state.cameraEnabled) "allowed" else "not_allowed"), click = { launch(Interaction.Permissions(arrayOf(Manifest.permission.CAMERA))) })
            SettingsRow("album_permission", R.drawable.ic_glyph_layers, status = t(state.albumStatus), click = { launch(Interaction.Permissions(MediaPermissions.albumRequest())) })
            SettingsRow("location_permission", R.drawable.ic_glyph_globe, status = t(if (state.locationEnabled) "allowed" else "not_allowed"), click = {
                launch(Interaction.Permissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)))
            })
            SettingsRow("background_location", R.drawable.ic_glyph_globe, detail = t("wifi_access_hint"), click = { launch(Interaction.Launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))) })
            Text(t("widgets") + "\n" + t("widget_hint"), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        }
        SettingsGroup("data", "transfer", R.drawable.ic_glyph_folder) {
            SettingsRow("import", R.drawable.ic_export, detail = t("xml_import_detail"), enabled = enabled, click = transfers.importXml)
            SettingsRow("export_all", R.drawable.ic_export, detail = t("export_all_detail"), enabled = enabled, click = transfers.exportAll)
            Text(t("workspace"), Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "workspace").absolutePath, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall) }
        }
        SettingsGroup("logs", "logs", R.drawable.ic_glyph_book) {
            SettingsRow("view_logs", R.drawable.ic_glyph_book, click = viewLogs)
            SettingsRow("export_logs", R.drawable.ic_export, click = transfers.exportLogs)
            SelectionContainer { Text(app.diagnostics.directory.absolutePath, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.res.colorResource(R.color.icon_background)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_shortcuts), t("app_name"), Modifier.size(56.dp))
                }
                Column { Text("Shortcuts", style = MaterialTheme.typography.titleMedium); Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable private fun SettingsGroup(id: String, title: String, icon: Int, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(id) { mutableStateOf(initiallyOpen) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "settings_expand")
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Surface(onClick = { open = !open }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(if (open) 20.dp else 28.dp), modifier = Modifier.testTag("settings_group_$id")) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { AppIcon(icon) } }
                    Text(t(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("⌄", Modifier.rotate(rotation), style = MaterialTheme.typography.titleLarge)
                }
            }
            AnimatedVisibility(open) { Column(Modifier.fillMaxWidth().padding(6.dp), content = content) }
        }
    }
}

@Composable private fun SettingsRow(title: String, icon: Int, detail: String? = null, status: String? = null, enabled: Boolean = true, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(4.dp).testTag("settings_$title")) {
        ListItem(headlineContent = { Text(t(title), style = MaterialTheme.typography.titleSmall) }, supportingContent = detail?.let { { Text(it) } },
            leadingContent = { AppIcon(icon) }, trailingContent = { if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) else Text("›", style = MaterialTheme.typography.titleLarge) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}
