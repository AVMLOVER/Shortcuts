package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun VariableGlyph() { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_variable), t("variables"), Modifier.size(20.dp), tint = VariableOrange) }

@Composable internal fun InlineOutputChip(name: String, preview: String?, id: String, choose: () -> Unit) {
    InputChip(false, choose, label = { Text(name, maxLines = 1) }, leadingIcon = { VariableGlyph() }, modifier = Modifier.testTag("choose_output_$id"))
    if (!preview.isNullOrEmpty()) Text(preview.take(512), style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun VariableInspector(name: String, preview: String?, replace: () -> Unit, remove: () -> Unit, close: () -> Unit, reveal: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("variable_inspector"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            VariableGlyph(); Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(close) { Text(t("close")) }
        }
        if (preview != null) Text(preview.take(512), style = MaterialTheme.typography.bodySmall, maxLines = 3)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(replace, modifier = Modifier.testTag("replace_variable")) { Text(t("replace_variable")) }
            if (reveal != null) FilledTonalButton(reveal, modifier = Modifier.testTag("reveal_variable")) { Text(t("reveal_source")) }
            TextButton(remove, modifier = Modifier.testTag("remove_variable")) { Text(t("remove")) }
        }
        HorizontalDivider()
    }
}
