package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.*

@Composable internal fun TimeZoneParameter(value: String, enabled: Boolean, changed: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("choose_timezone")) {
        Text(t("zone") + " · " + value.ifBlank { t("system") + " (${ZoneId.systemDefault().id})" })
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val zones = remember { (listOf("UTC", "Asia/Shanghai", "Asia/Tokyo", "Asia/Taipei", "Europe/Berlin", "Europe/London", "America/New_York", "America/Los_Angeles") + ZoneId.getAvailableZoneIds().sorted()).distinct() }
        val matches = remember(zones, query) { zones.filter { it.contains(query, ignoreCase = true) } }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(t("zone")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(t("search_timezone")) }, singleLine = true, modifier = Modifier.testTag("timezone_search"))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { TextButton(onClick = { changed(""); open = false }, modifier = Modifier.testTag("timezone_system")) { Text(t("system") + " (${ZoneId.systemDefault().id})") } }
                    items(matches, key = { it }) { zone -> TextButton(onClick = { changed(zone); open = false }, modifier = Modifier.fillMaxWidth().testTag("timezone_$zone")) { Text(zone) } }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text(t("cancel")) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateParameterPresets(value: String, enabled: Boolean, time: Boolean, end: Boolean = false, changed: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun dateValue(date: LocalDate): String = if (time) date.atTime(if (end) 10 else 9, 0).toString() else date.toString()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { changed(dateValue(LocalDate.now())) }, enabled = enabled) { Text(t("today")) }
        TextButton(onClick = { changed(dateValue(LocalDate.now().plusDays(1))) }, enabled = enabled) { Text(t("tomorrow")) }
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("pick_date")) { Text(t("choose_date")) }
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { changed(dateValue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())) }; open = false
        }, enabled = picker.selectedDateMillis != null) { Text(t("done")) } }, dismissButton = { TextButton(onClick = { open = false }) { Text(t("cancel")) } }) { DatePicker(picker) }
    }
}

@Composable internal fun DateFormatPresets(enabled: Boolean, changed: (String) -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text(t("format_presets")) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(t("format_presets")) }, text = {
        Column { listOf(com.germanium.shortcuts.core.defaultCurrentDateTimePattern(locale), "yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "HH:mm", "EEE, MMM d, yyyy", "yyyy-MM-dd'T'HH:mm:ssXXX").distinct().forEach { format ->
            TextButton(onClick = { changed(format); open = false }) { Text(format) }
        } }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text(t("cancel")) } })
}
