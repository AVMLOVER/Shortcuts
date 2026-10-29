package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.Value

internal fun widgetInteractionSupported(request: Interaction) = request is Interaction.Input || request is Interaction.Menu || request is Interaction.Checklist || request is Interaction.ResultDialog || request is Interaction.QuickLook

@Composable internal fun WidgetOverlayContent(app: ShortcutsApplication, request: Interaction?, output: String, close: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(12.dp).testTag("widget_overlay"),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh,shadowElevation=12.dp) {
        Column(Modifier.padding(20.dp).heightIn(max=560.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val title = when(request) { is Interaction.Input -> request.title; is Interaction.Menu -> request.title; is Interaction.Checklist -> request.title; is Interaction.ResultDialog -> request.title; else -> t("app_name") }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(title.ifBlank { t("app_name") },Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                if (request == null) IconButton(onClick=close) { AppIcon(R.drawable.ic_close,description=t("close")) }
            }
            when(request) {
                is Interaction.Input -> {
                    var answer by remember(request.id) { mutableStateOf(request.initial) }
                    OutlinedTextField(answer,{ if(it.length<=262144) answer=it },placeholder={Text(request.prompt)},modifier=Modifier.fillMaxWidth().testTag("widget_overlay_input"),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=if(request.type=="number") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),maxLines=5)
                    val valid = when(request.type) { "number" -> runCatching { com.germanium.shortcuts.core.parseNumber(answer) }.isSuccess; "date" -> runCatching { com.germanium.shortcuts.core.parseEventTime(answer) }.isSuccess; else -> true }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={ app.interactions.reply(request,null) }) { Text(t("cancel")) }
                        Button(onClick={app.interactions.reply(request,answer)},enabled=valid,modifier=Modifier.testTag("widget_overlay_confirm")) { Text(t("done")) }
                    }
                }
                is Interaction.Menu -> {
                    Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        request.items.forEach { (id,label) -> FilledTonalButton(onClick={app.interactions.reply(request,id)},modifier=Modifier.fillMaxWidth()) { Text(label) } }
                    }
                    TextButton(onClick={app.interactions.reply(request,null)}) { Text(t("cancel")) }
                }
                is Interaction.Checklist -> {
                    var selected by remember(request.id) { mutableStateOf(setOf<String>()) }
                    Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())) {
                        request.items.forEach { (id,label) -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Checkbox(id in selected,{ selected=if(it) selected+id else selected-id }); Text(label,Modifier.weight(1f)) } }
                    }
                    Row { TextButton(onClick={app.interactions.reply(request,null)}) { Text(t("cancel")) }; Button(onClick={app.interactions.reply(request,request.items.map { it.first }.filter { it in selected }.joinToString(","))}) {Text(t("done"))} }
                }
                is Interaction.ResultDialog -> {
                    Text(request.body,Modifier.weight(1f,false).verticalScroll(rememberScrollState()))
                    Row { if(request.allowCancel) TextButton(onClick={app.interactions.reply(request,null)}) {Text(t("cancel"))}; Button(onClick={app.interactions.reply(request,"ok")},modifier=Modifier.testTag("widget_overlay_confirm")) { Text(t("done")) } }
                }
                else -> {
                    QuickLookContent(app, (request as? Interaction.QuickLook)?.value ?: Value.Text(output), Modifier.weight(1f,false))
                    if(request != null) Button(onClick={app.interactions.reply(request,"ok")}) {Text(t("done"))}
                }
            }
        }
    }
}
