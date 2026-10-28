package com.germanium.shortcuts

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PdfSaveDirectory(value: String, enabled: Boolean, change: (String) -> Unit, error: (Throwable) -> Unit) {
    val context = LocalContext.current
    val folderLabel by produceState(initialValue = Uri.decode(value.substringAfterLast('/')).substringAfter(':'), key1 = value) {
        if (value.isNotBlank()) {
            val name = withContext(Dispatchers.IO) { runCatching {
                val tree = Uri.parse(value)
                context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() }
            if (!name.isNullOrBlank()) this.value = name
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); change(uri.toString()) } catch (e: Exception) { error(e) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("default_save_directory"), style = MaterialTheme.typography.labelLarge)
        Text(if (value.isBlank()) t("pdf_choose_on_run") else folderLabel, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { try { picker.launch(value.takeIf(String::isNotBlank)?.let(Uri::parse)) } catch (e: Exception) { error(e) } }, enabled = enabled) { AppIcon(R.drawable.ic_glyph_folder); Spacer(Modifier.width(8.dp)); Text(t("choose_save_directory")) }
            if (value.isNotEmpty()) TextButton(onClick = { change("") }, enabled = enabled) { Text(t("remove")) }
        }
    }
}

internal suspend fun savePdf(app: ShortcutsApplication, directory: String, filename: String, bytes: ByteArray): Value {
    var created = false
    val resolver = app.contentResolver
    val defaultTarget = withContext(Dispatchers.IO) {
        val tree = directory.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (tree != null && tree.scheme == "content" && DocumentsContract.isTreeUri(tree) && resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) {
            try { DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/pdf", filename)?.also { created = true } }
            catch (_: SecurityException) { null } catch (_: java.io.FileNotFoundException) { null } catch (_: IllegalArgumentException) { null }
        } else null
    }
    val target = defaultTarget ?: app.interactions.ask(Interaction.SavePdf(filename))?.let(Uri::parse) ?: throw kotlinx.coroutines.CancellationException("PDF save cancelled")
    try {
        withContext(Dispatchers.IO) { resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: throw Problem("err_io") }
        return Value.Text(target.toString())
    } catch (e: Exception) { if (created) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, target) } }; throw e }
}
