package com.germanium.shortcuts

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class,ExperimentalMaterial3ExpressiveApi::class)
@Composable internal fun AutomationTimePicker(value:String,onChange:(String)->Unit) {
    var open by rememberSaveable{mutableStateOf(false)}
    val configuration=LocalConfiguration.current
    val parsed=remember(value){runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.of(9,0))}
    val locale=configuration.locales[0]
    val is24Hour=DateFormat.is24HourFormat(LocalContext.current)
    val label=remember(parsed,locale,is24Hour){parsed.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,if(is24Hour)"Hm" else "hm"),locale))}
    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        FilledTonalButton(onClick={open=true},modifier=Modifier.testTag("automation_choose_time")){Text(t("select_time"))}
        Text(label,style=MaterialTheme.typography.titleLarge)
    }
    if(open){
        val state=rememberTimePickerState(initialHour=parsed.hour,initialMinute=parsed.minute,is24Hour=is24Hour)
        var keyboard by rememberSaveable{mutableStateOf(configuration.screenHeightDp<480)}
        VibrantTimePickerDialog(onDismissRequest={open=false},confirmButton={TextButton(onClick={onChange(String.format(Locale.ROOT,"%02d:%02d",state.hour,state.minute));open=false},modifier=Modifier.testTag("automation_time_confirm")){Text(t("confirm"))}},dismissButton={TextButton(onClick={open=false}){Text(t("cancel"))}},modeToggleButton={TextButton(onClick={keyboard=!keyboard}){Text(t(if(keyboard)"time_dial" else "time_keyboard"))}}){
            Text(t("select_time"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=12.dp))
            if(keyboard)TimeInput(state=state,colors=TimeInputDefaults.vibrantColors()) else TimePicker(state=state,colors=TimePickerDefaults.vibrantColors(),shapes=TimePickerDefaults.shapes())
        }
    }
}
