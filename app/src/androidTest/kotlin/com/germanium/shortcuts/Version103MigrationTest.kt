package com.germanium.shortcuts

import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The original backup is kept; old documents are compared structurally after their metadata upgrade. */
class Version103MigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun verifyOriginalDataAfterMigrationAndTests() {
        val name=InstrumentationRegistry.getArguments().getString("snapshotDir") ?: error("Pass snapshotDir")
        require(name.matches(Regex("codex-verify102-[0-9]+")))
        val snapshot=File(context.getExternalFilesDir(null),name)
        val baseline=JSONObject(File(snapshot,"baseline.json").readText());val hashes=baseline.getJSONObject("files")
        val roots=mapOf("internal" to context.filesDir,"workspace" to File(context.getExternalFilesDir(null),"workspace"))
        ZipFile(File(snapshot,"data.zip")).use { zip -> hashes.keys().forEach { path ->
            val current=File(roots.getValue(path.substringBefore('/')),path.substringAfter('/'))
            assertTrue("Original file missing",current.isFile)
            if (listOf("internal/shortcuts/","internal/folders/","internal/drafts/").any { path.startsWith(it) }) {
                val before=zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                assertEquals("Migration changed workflow content",XmlCodec().decode(before),XmlCodec().decode(current.readBytes()))
                if (!path.startsWith("internal/drafts/")) assertTrue(current.readText().contains("appVersion=\"10003\""))
            } else {
                val hash=MessageDigest.getInstance("SHA-256").digest(current.readBytes()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
                assertEquals("Original file changed",hashes.getString(path),hash)
            }
        } }
        val preferences=baseline.getJSONObject("preferences")
        preferences.keys().forEach { file -> val expected=preferences.getJSONObject(file);val current=context.getSharedPreferences(file,0).all
            expected.keys().forEach { key -> val value=current[key];val encoded=if(value is Set<*>) "set:"+value.map { it.toString() }.sorted().joinToString("\u0000") else "${value?.javaClass?.simpleName}:$value"
                assertEquals("Preference changed",expected.getString(key),encoded)
            }
        }
        println("SHORTCUTS_103_DATA_PRESERVED files=${hashes.length()}")
    }
}
