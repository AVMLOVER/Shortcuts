package com.germanium.shortcuts

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.ThemeMode
import kotlinx.coroutines.launch

/** Permission guidance never starts the requested shortcut automatically. */
class WidgetPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableShortcutsEdgeToEdge()
        val app=application as ShortcutsApplication
        setContent {
            var theme by remember { mutableStateOf(ThemeMode.SYSTEM) }; LaunchedEffect(Unit) { theme=app.settings.load().theme }
            ShortcutsTheme(theme) { Scaffold(contentWindowInsets=shortcutsContentInsets()) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("widget_permission_required"),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Text(t("overlay_permission"),style=MaterialTheme.typography.headlineMedium)
                    Text(t("widget_overlay_required"),style=MaterialTheme.typography.bodyLarge)
                    Button(onClick={ startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }) { Text(t("authorize")) }
                    TextButton(onClick={finish()}) { Text(t("close")) }
                }
            } }
        }
    }
    override fun onResume() { super.onResume(); val app=application as ShortcutsApplication; app.scope.launch { runCatching { ShortcutWidgets.updateAll(app) } } }
}
