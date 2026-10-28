package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.RunResult

@Composable internal fun ActionIcon(definition: ActionDefinition?) {
    if (definition?.group == "files") AppIcon(when (definition.type) {
        "create_folder", "list_files" -> R.drawable.ic_glyph_folder
        "write_file", "append_file", "create_file" -> R.drawable.ic_file_write
        "delete_file" -> R.drawable.ic_delete
        else -> R.drawable.ic_file_read
    }) else Text(definition?.symbol ?: "?", style = MaterialTheme.typography.titleLarge)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ToolIcon(icon: Int, label: String, enabled: Boolean = true, danger: Boolean = false, click: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(t(label)) } }, state = rememberTooltipState()) {
        IconButton(onClick = click, enabled = enabled) { Icon(painterResource(icon), t(label), tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun HomeResult(result: RunResult) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        SelectionContainer { Text(result.output.ifEmpty { t("no_output") }, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}
@Composable internal fun LiveShell(stdout: String, stderr: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (stdout.isNotEmpty()) Text(stdout.takeLast(8192), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (stderr.isNotEmpty()) Text(stderr.takeLast(8192), color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
