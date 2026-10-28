package com.germanium.shortcuts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.germanium.shortcuts.core.*

internal fun outputPropertyLabel(type: String, property: String) = if (type == "repeat_until" && property == "repeatCount") "repeat_count" else "${if (type == "current_location") "location" else "image"}_prop_${property.lowercase(java.util.Locale.ROOT)}"

@Composable internal fun RepeatEndCondition(step: ActionStep, enabled: Boolean, variables: VariableSession, names: Map<String, String>, focused: (String) -> Unit, change: (String, String) -> Unit) {
    Text(t("repeat_until_hint"), style = MaterialTheme.typography.bodySmall)
    VariableTextField("${step.id}:left", step.parameters["left"] ?: "{{input}}", { change("left", it) }, t("repeat_output"), variables, names, enabled = enabled, focused = { focused("left") }, modifier = Modifier.testTag("repeat_condition_output_${step.id}"))
    Text(t("repeat_condition"), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ShortcutEngine.CONDITIONS.forEach { condition ->
        val selected = (step.parameters["operator"] ?: "equals") == condition
        FilterChip(selected, { change("operator", condition) }, enabled = enabled,
            label = { Text(t(condition), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
            leadingIcon = if (selected) ({ AppIcon(R.drawable.ic_glyph_check, Modifier.size(18.dp)) }) else null,
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary),
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.testTag("repeat_operator_${step.id}_$condition"))
    } }
    if ((step.parameters["operator"] ?: "equals") !in listOf("is_empty", "is_not_empty", "is_true")) VariableTextField("${step.id}:right", step.parameters["right"].orEmpty(), { change("right", it) }, t("repeat_compare"), variables, names, enabled = enabled, focused = { focused("right") }, modifier = Modifier.testTag("repeat_condition_compare_${step.id}"))
}
