package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal fun missingReference(name: String, names: Map<String, String>): Boolean =
    name !in names && (!name.startsWith('@') || "@${name.drop(1).substringBefore('.')}" !in names)

@Composable internal fun VariableSourcePicker(id: String, selected: String, known: List<String>, names: Map<String, String>, enabled: Boolean,
    choose: (String) -> Unit, magic: () -> Unit) {
    var expanded by remember(id) { mutableStateOf(false) }
    val missing = missingReference(selected, names)
    Text(t("variable_name"), style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("variable_source_$id"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VariableGlyph(); Text(if (missing) t("missing_variable") else names[selected] ?: selected, Modifier.weight(1f)); Text("▾")
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(t("select_output")) }, leadingIcon = { VariableGlyph() }, onClick = { expanded = false; magic() }, modifier = Modifier.testTag("variable_source_magic_$id"))
            DropdownMenuItem(text = { Text(t("previous_result")) }, onClick = { expanded = false; choose("input") }, modifier = Modifier.testTag("variable_source_input_$id"))
            known.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; choose(name) }, modifier = Modifier.testTag("variable_source_named_${id}_$name")) }
        }
    }
    if (missing) Text(t("missing_variable_hint"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
