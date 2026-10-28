package com.germanium.shortcuts

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class TransferTools(val export: (Shortcut) -> Unit, val importXml: () -> Unit, val exportLogs: () -> Unit, val exportAll: () -> Unit)
@Composable fun rememberTransfers(model: ShortcutsViewModel): TransferTools {
    val context = LocalContext.current
    val app = context.applicationContext as ShortcutsApplication
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val token = pending; pending = null
        if (token != null) scope.launch {
            val source = File(app.cacheDir, "export-$token.xml")
            try { if (uri != null) { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: throw Problem("err_io") }; model.notice("exported") }
            } catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) } finally { withContext(NonCancellable + Dispatchers.IO) { source.delete() } }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { XmlCodec().decodeImport(it.readBytesBounded(XmlCodec.MAX_BUNDLE_BYTES)) } ?: throw Problem("err_xml") }
                model.importShortcuts(imported)
            } catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) }
        }
    }
    val logs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(app.diagnostics.read()) } ?: throw Problem("err_io") }; model.notice("exported") } catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) } }
    }
    return TransferTools(export = { shortcut -> scope.launch {
        try {
            val token = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) { File(app.cacheDir, "export-$token.xml").writeBytes(XmlCodec().encode(shortcut)) }
            pending = token
            val filename = shortcut.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".xml"
            create.launch(filename)
        } catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) }
    } }, importXml = { runCatching { open.launch(arrayOf("application/xml", "text/xml", "application/octet-stream", "text/plain")) }.onFailure(model::report) }, exportLogs = { runCatching { logs.launch("Shortcuts-logs.txt") }.onFailure(model::report) }, exportAll = { scope.launch {
        try {
            val token = UUID.randomUUID().toString()
            val items = app.shortcuts.list()
            withContext(Dispatchers.IO) { File(app.cacheDir, "export-$token.xml").writeBytes(XmlCodec().encodeBundle(items)) }
            pending = token; create.launch("Shortcuts-all.xml")
        } catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) }
    } })
}
