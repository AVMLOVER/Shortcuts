package com.germanium.shortcuts

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ShareReceiverState(val payload: Value? = null, val shortcuts: List<Shortcut> = emptyList(), val selected: String? = null, val error: String? = null)
class ShareReceiverModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as ShortcutsApplication
    private var loaded = false
    private val mutable = MutableStateFlow(ShareReceiverState())
    val state = mutable.asStateFlow()
    fun receive(intent: Intent) {
        if (loaded) return
        loaded = true
        viewModelScope.launch { try {
            val input = AndroidContent(app).receive(intent)
            val preferred = intent.getStringExtra("shortcutId") ?: intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("share-")
            val shortcuts = app.shortcuts.list().filter { ContentInput.accepts(it, input) }.sortedWith(compareByDescending<Shortcut> { it.id == preferred }.thenBy { it.name })
            mutable.update { it.copy(payload = input, shortcuts = shortcuts) }
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(error = app.describe(e)) } } }
    }
    fun start(shortcut: Shortcut) { viewModelScope.launch { try {
        val actual = app.shortcuts.list().find { it.id == shortcut.id } ?: throw Problem("err_shared_input")
        val input = state.value.payload ?: throw Problem("err_shared_input")
        require(ContentInput.accepts(actual, input)) { throw Problem("err_shared_input") }
        if (app.runs.start(actual, app.settings.load().mode, input = input) == null) throw Problem("err_busy")
        mutable.update { it.copy(selected = actual.id, error = null) }
    } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(error = app.describe(e)) } } } }
}

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val app = application as ShortcutsApplication
        setContent {
            val model: ShareReceiverModel = viewModel()
            val receiver by model.state.collectAsStateWithLifecycle()
            val run by app.runs.state.collectAsStateWithLifecycle()
            val mode by produceState(ThemeMode.SYSTEM) { value = app.settings.load().theme }
            LaunchedEffect(Unit) { model.receive(intent) }
            ShortcutsTheme(mode) {
                ShareSheet(app, receiver.payload, receiver.shortcuts, run, receiver.selected, receiver.error, dismiss = { if (receiver.selected != null && run.runningId == receiver.selected) app.runs.cancel(); finish() }, start = model::start)
                InteractionHost(app)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ShareSheet(app: ShortcutsApplication, payload: Value?, shortcuts: List<Shortcut>, run: RunState, selected: String?, error: String?, dismiss: () -> Unit, start: (Shortcut) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(t("run_shared_content"), style = MaterialTheme.typography.headlineSmall) }
            item { Text(t("share_input_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (payload == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            payload?.let { input -> item {
                Text(ContentInput.extract("text", input).text().take(512).ifBlank { t("share_file_count", ContentInput.flatten(ContentInput.field(input, "files") ?: Value.ListValue(emptyList())).size) }, maxLines = 4)
            } }
            if (payload != null && shortcuts.isEmpty()) item { Text(t("share_no_shortcuts")) }
            if (selected == null) items(shortcuts, key = { it.id }) { shortcut ->
                Card(onClick = { start(shortcut) }, enabled = run.runningId == null) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShortcutBadge(shortcut.icon, shortcut.color, 44.dp); Text(shortcut.name, Modifier.weight(1f)); AppIcon(R.drawable.ic_play)
                } }
            }
            if (selected != null && run.shortcutId == selected) {
                if (run.runningId != null) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text(t("running"), Modifier.weight(1f).padding(12.dp)); TextButton(onClick = app.runs::cancel) { Text(t("stop")) } } }
                run.result?.let { result -> item { HomeResult(result) } }
                run.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
            item { TextButton(onClick = dismiss) { Text(t("close")) } }
        }
    }
}
