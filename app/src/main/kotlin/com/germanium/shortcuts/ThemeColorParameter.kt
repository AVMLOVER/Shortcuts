package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ThemeColorParameter(value: String, foreground: Boolean, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fallback = if (foreground) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(t("custom_color")) }
        TextButton(onClick = { change("") }, enabled = enabled && value.isNotEmpty()) { Text(t("theme_color")) }
    }
    if (open) ModalBottomSheet(contentWindowInsets = { shortcutsContentInsets() }, onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            M3EColorPicker(value.takeIf { it.startsWith('#') } ?: "#%06X".format(java.util.Locale.ROOT, fallback.toArgb() and 0xFFFFFF), change)
            TextButton(onClick = { open = false }) { Text(t("done")) }
        }
    }
}
