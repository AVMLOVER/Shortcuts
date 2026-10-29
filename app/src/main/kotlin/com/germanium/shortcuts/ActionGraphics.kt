package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.RunResult
import com.germanium.shortcuts.core.Value
import kotlinx.coroutines.launch

@Composable internal fun ActionIcon(definition: ActionDefinition?) {
    if (definition?.group == "files") AppIcon(when (definition.type) {
        "create_folder", "list_files" -> R.drawable.ic_glyph_folder
        "write_file", "append_file", "create_file" -> R.drawable.ic_file_write
        "delete_file" -> R.drawable.ic_delete
        else -> R.drawable.ic_file_read
    }) else Text(definition?.symbol ?: "?", style = if (definition?.type == "format_number") MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ToolIcon(icon: Int, label: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, click: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(t(label)) } }, state = rememberTooltipState()) {
        IconButton(onClick = click, enabled = enabled, modifier = modifier) { Icon(painterResource(icon), t(label), tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun HomeResult(result: RunResult) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp)) {
            QuickLookContent(LocalContext.current.applicationContext as ShortcutsApplication, result.outputValue, Modifier.weight(1f))
            ShareValueButton(result.outputValue)
        }
    }
}
@Composable internal fun ShareValueButton(value: Value) {
    val app = LocalContext.current.applicationContext as ShortcutsApplication
    val scope = rememberCoroutineScope()
    ToolIcon(R.drawable.ic_export, "action_share") { scope.launch {
        try { app.actions.share(value) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; android.widget.Toast.makeText(app, app.describe(e), android.widget.Toast.LENGTH_SHORT).show() }
    } }
}
@Composable internal fun LiveShell(stdout: String, stderr: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (stdout.isNotEmpty()) Text(stdout.takeLast(8192), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (stderr.isNotEmpty()) Text(stderr.takeLast(8192), color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
