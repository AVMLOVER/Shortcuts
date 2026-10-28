package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

@Composable internal fun NumericParameterControls(value: String, unit: String, amplitude: Boolean, enabled: Boolean, change: (String) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val number = value.toBigDecimalOrNull()
    val maximum = if (amplitude) 255.toBigDecimal() else if (unit == "seconds") 60.toBigDecimal() else 60000.toBigDecimal()
    val minimum = if (amplitude) BigDecimal.ONE else BigDecimal.ZERO
    val increment = if (amplitude) BigDecimal.ONE else if (unit == "seconds") BigDecimal("0.1") else 100.toBigDecimal()
    fun alter(delta: BigDecimal) { number?.let { change((it + delta).coerceIn(minimum, maximum).stripTrailingZeros().toPlainString()) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = { alter(-increment) }, enabled = enabled && number != null) { Text("−", style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = { dialog = true }, enabled = enabled) { Text(t("adjust_value")) }
        FilledTonalIconButton(onClick = { alter(increment) }, enabled = enabled && number != null) { AppIcon(R.drawable.ic_add) }
    }
    if (dialog) {
        var input by remember { mutableStateOf(value.takeIf { number != null } ?: minimum.toPlainString()) }
        val valid = input.toBigDecimalOrNull()?.let { it in minimum..maximum } == true
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text(t(if (amplitude) "amplitude" else "duration")) }, text = {
            Column {
                OutlinedTextField(input, { input = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), supportingText = { Text(if (amplitude) "1–255" else t(unit)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FilledTonalIconButton(onClick = { input = ((input.toBigDecimalOrNull() ?: minimum) - increment).coerceIn(minimum, maximum).stripTrailingZeros().toPlainString() }) { Text("−") }
                    FilledTonalIconButton(onClick = { input = ((input.toBigDecimalOrNull() ?: minimum) + increment).coerceIn(minimum, maximum).stripTrailingZeros().toPlainString() }) { AppIcon(R.drawable.ic_add) }
                }
            }
        }, confirmButton = { TextButton(onClick = { change(input); dialog = false }, enabled = valid) { Text(t("done")) } }, dismissButton = { TextButton(onClick = { dialog = false }) { Text(t("cancel")) } })
    }
}
