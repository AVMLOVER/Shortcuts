package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FolderEditor(original: ShortcutFolder? = null, dismiss: () -> Unit, save: (ShortcutFolder) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(original?.icon ?: "folder") }
    var color by rememberSaveable { mutableStateOf(original?.color ?: "purple") }
    var styling by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t("new_folder"), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { styling = true }, shape = MaterialTheme.shapes.large) { ShortcutBadge(icon, color, 64.dp) }
                OutlinedTextField(name, { if (it.length <= 200) name = it }, label = { Text(t("name")) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = { save(ShortcutFolder(original?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), icon, color)); dismiss() }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(t("save")) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (styling) ShortcutAppearancePicker(icon, color, { styling = false }) { i, c -> icon = i; color = c }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MoveToFolderSheet(shortcut: Shortcut, folders: List<ShortcutFolder>, dismiss: () -> Unit, move: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("move_to_folder"), style = MaterialTheme.typography.headlineSmall)
            Text(shortcut.name)
            FilledTonalButton(onClick = { move(""); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(t("no_folder")) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(folders, key = { it.id }) { folder ->
                Card(onClick = { move(folder.id); dismiss() }) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShortcutBadge(folder.icon, folder.color, 40.dp); Text(folder.name)
                } }
            } }
        }
    }
}
