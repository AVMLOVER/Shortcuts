package com.germanium.shortcuts

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun HtmlPreviewDialog(html: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(t("action_view_html")) }, text = {
        AndroidView(factory = { context -> WebView(context).apply {
            settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.domStorageEnabled = false
        } }, update = { it.loadDataWithBaseURL("https://shortcuts.local/", html, "text/html", "UTF-8", null) }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxWidth().height(400.dp))
    }, confirmButton = { TextButton(onClick = close) { Text(t("close")) } })
}
