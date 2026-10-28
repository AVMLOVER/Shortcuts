package com.germanium.shortcuts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** No application-model APIs, so a same-signed runner can snapshot an older installed release. */
@RunWith(AndroidJUnit4::class)
class DataPreservationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun destination(): File {
        val name = InstrumentationRegistry.getArguments().getString("snapshotDir") ?: error("Pass snapshotDir")
        require(name.matches(Regex("codex-verify102-[0-9]+")))
        return File(context.getExternalFilesDir(null), name).also { check(it.isDirectory || it.mkdirs()) }
    }
    private fun roots() = linkedMapOf("internal" to context.filesDir, "workspace" to File(context.getExternalFilesDir(null), "workspace"))
    private fun files() = roots().flatMap { (name, root) -> root.walkTopDown().filter { it.isFile && (name == "workspace" || it.extension == "xml") && it.parentFile.name !in listOf("logs", "crashes") }.map { "$name/${it.relativeTo(root).invariantSeparatorsPath}" to it }.toList() }.toMap()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun preferences(): JSONObject {
        val result = JSONObject()
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
            val values = JSONObject()
            context.getSharedPreferences(file.nameWithoutExtension, 0).all.forEach { (key, value) -> values.put(key, if (value is Set<*>) "set:" + value.map { it.toString() }.sorted().joinToString("\u0000") else "${value?.javaClass?.simpleName}:$value") }
            result.put(file.nameWithoutExtension, values)
        }
        return result
    }
    @Test fun snapshotBeforeUpgrade() {
        val directory = destination(); check(!File(directory, "baseline.json").exists())
        val originals = files(); val hashes = JSONObject(); originals.forEach { (path, file) -> hashes.put(path, hash(file)) }
        var size = 0L
        ZipOutputStream(File(directory, "data.zip").outputStream().buffered()).use { zip ->
            for ((path, file) in originals) { size += file.length(); require(size <= 256L * 1024 * 1024) { "Backup exceeds limit" }; zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach { file -> zip.putNextEntry(ZipEntry("shared_prefs/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
        File(directory, "baseline.json").writeText(JSONObject().put("files", hashes).put("preferences", preferences()).toString())
        println("SHORTCUTS_SNAPSHOT files=${originals.size} bytes=$size directory=${directory.name}")
    }
    @Test fun verifyOriginalDataAfterUpgradeAndTests() {
        val baseline = JSONObject(File(destination(), "baseline.json").readText())
        val hashes = baseline.getJSONObject("files"); val current = files()
        hashes.keys().forEach { key -> val file = current[key]; assertNotNull("Original file missing: $key", file); assertEquals("Original file changed: $key", hashes.getString(key), hash(file!!)) }
        val previous = baseline.getJSONObject("preferences"); val preferences = preferences()
        previous.keys().forEach { name -> val old = previous.getJSONObject(name); val now = preferences.optJSONObject(name); assertNotNull("Preferences missing: $name", now); old.keys().forEach { key -> assertEquals("Preference changed: $name/$key", old.getString(key), now!!.optString(key, "missing")) } }
        println("SHORTCUTS_DATA_PRESERVED files=${hashes.length()} preferenceFiles=${previous.length()}")
    }
}
