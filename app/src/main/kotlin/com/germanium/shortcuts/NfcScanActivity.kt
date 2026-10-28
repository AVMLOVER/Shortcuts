package com.germanium.shortcuts

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.ThemeMode

internal fun Tag.hexId() = id.joinToString("") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 255) }
class NfcScanActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setResult(Activity.RESULT_CANCELED)
        setContent { ShortcutsTheme(ThemeMode.SYSTEM) { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(t("scan_nfc"), style = MaterialTheme.typography.headlineMedium)
            Text(t(if (adapter == null) "nfc_unavailable" else if (adapter?.isEnabled == true) "nfc_scan_hint" else "nfc_enable_hint"))
            if (adapter != null && adapter?.isEnabled == false) Button(onClick = { startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }) { Text(t("settings")) }
            TextButton(onClick = { finish() }) { Text(t("cancel")) }
        } } } }
    }
    override fun onResume() { super.onResume(); adapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null) }
    override fun onPause() { adapter?.disableReaderMode(this); super.onPause() }
    override fun onTagDiscovered(tag: Tag) { runOnUiThread { setResult(Activity.RESULT_OK, Intent().putExtra("tagId", tag.hexId())); finish() } }
}
