package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.Shortcut

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutTargetPicker(selected: String, shortcuts: List<Shortcut>, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = shortcuts.firstOrNull { it.uuid == selected }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("choose_called_shortcut")) {
        Text(current?.name ?: t(if (selected.isBlank()) "choose_shortcut" else "err_shortcut_missing"))
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(t("choose_shortcut"), style = MaterialTheme.typography.titleLarge) }
            items(shortcuts, key = { it.uuid }) { shortcut ->
                Surface(onClick = { choose(shortcut.uuid); expanded = false }, shape = MaterialTheme.shapes.large, color = if (shortcut.uuid == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    ListItem(headlineContent = { Text(shortcut.name) }, leadingContent = { ShortcutBadge(shortcut.icon, shortcut.color, 40.dp) })
                }
            }
            if (shortcuts.isEmpty()) item { Text(t("search_no_results")) }
        }
    }
}
