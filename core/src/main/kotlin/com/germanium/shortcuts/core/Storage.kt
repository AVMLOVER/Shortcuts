package com.germanium.shortcuts.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ShortcutRepository {
    suspend fun list(): List<Shortcut>
    suspend fun save(shortcut: Shortcut)
    suspend fun delete(id: String)
}
interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}

/** One instance per app; serialize read-modify-write at the repository boundary. */
class XmlShortcutRepository(private val directory: File, private val codec: XmlCodec = XmlCodec()) : ShortcutRepository {
    private val mutex = Mutex()
    override suspend fun list(): List<Shortcut> = withContext(Dispatchers.IO) { mutex.withLock {
        if (!directory.exists()) return@withLock emptyList()
        check(directory.isDirectory) { throw Problem("err_io") }
        val files = checkNotNull(directory.listFiles()) { throw Problem("err_io") }
        files.filter { it.extension == "xml" }.map { file ->
            codec.decode(file.readBounded()).also { require(file.nameWithoutExtension == it.id) { throw Problem("err_io") } }
        }.sortedByDescending { it.updatedAt }
    } }
    override suspend fun save(shortcut: Shortcut): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        atomicWrite(file(shortcut.id), codec.encode(shortcut))
    } }
    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        Files.deleteIfExists(file(id).toPath())
        Unit
    } }
    suspend fun importNew(shortcuts: List<Shortcut>): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        require(shortcuts.size <= 1000 && shortcuts.map { it.id }.distinct().size == shortcuts.size) { throw Problem("err_size") }
        require(shortcuts.none { file(it.id).exists() }) { throw Problem("err_io") }
        val documents = shortcuts.map { it.id to codec.encode(it) }
        val written = mutableListOf<File>()
        try { for ((id, bytes) in documents) { val target = file(id); atomicWrite(target, bytes); written += target } }
        catch (e: Exception) { written.forEach { it.delete() }; throw e }
    } }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { throw Problem("err_io") }
        return File(directory, "$id.xml")
    }
}

class XmlSettingsRepository(private val file: File, private val codec: XmlCodec = XmlCodec()) : SettingsRepository {
    private val mutex = Mutex()
    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) { mutex.withLock {
        if (file.exists()) codec.decodeSettings(file.readBounded()) else AppSettings()
    } }
    override suspend fun save(settings: AppSettings): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        atomicWrite(file, codec.encode(settings))
    } }
}

private fun File.readBounded(): ByteArray = inputStream().use { stream ->
    val buffer = ByteArray(XmlCodec.MAX_BYTES + 1)
    var count = 0
    while (count < buffer.size) {
        val read = stream.read(buffer, count, buffer.size - count)
        if (read < 0) break
        count += read
    }
    require(count <= XmlCodec.MAX_BYTES) { throw Problem("err_io") }
    buffer.copyOf(count)
}

/** Same-directory atomic replacement; failure leaves the previously committed document untouched. */
internal fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = checkNotNull(target.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { throw Problem("err_io") }
    val temporary = File.createTempFile(".pending-", ".tmp", parent)
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
