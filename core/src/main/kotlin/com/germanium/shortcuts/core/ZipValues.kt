package com.germanium.shortcuts.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ArchiveEntry(val name: String, val bytes: ByteArray)
object ZipValues {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun safeName(name: String): String {
        require(name.isNotBlank() && name.length <= 400 && !name.startsWith('/') && '\\' !in name && ':' !in name && name.none { it.code < 32 } && name.trimEnd('/').split('/').none { it.isBlank() || it == "." || it == ".." }) { throw Problem("err_archive") }
        return name.trimEnd('/')
    }
    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(b: Int) { require(count < MAX_BYTES) { throw Problem("err_size") }; super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { require(count.toLong()+len <= MAX_BYTES) { throw Problem("err_size") }; super.write(b,off,len) }
    }
    fun pack(entries: List<ArchiveEntry>): ByteArray {
        require(entries.isNotEmpty() && entries.size <= 256 && entries.sumOf { it.bytes.size.toLong() } <= MAX_BYTES) { throw Problem("err_size") }
        val names = entries.map { safeName(it.name) }; require(names.distinct().size == names.size) { throw Problem("err_archive") }
        val output = BoundedOutput()
        ZipOutputStream(output).use { zip -> entries.forEach { item -> zip.putNextEntry(ZipEntry(item.name)); zip.write(item.bytes); zip.closeEntry() } }
        return output.toByteArray()
    }
    fun unpack(bytes: ByteArray): List<ArchiveEntry> {
        require(bytes.size in 4..MAX_BYTES && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) { throw Problem("err_archive") }
        val result = mutableListOf<ArchiveEntry>(); val names = mutableSetOf<String>(); var total = 0L; var count = 0
        try { ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                require(++count <= 256) { throw Problem("err_size") }
                val name = safeName(item.name); require(names.add(name)) { throw Problem("err_archive") }
                if (item.isDirectory) { require(zip.read() == -1) { throw Problem("err_archive") }; zip.closeEntry(); continue }
                require(item.size <= MAX_BYTES) { throw Problem("err_size") }
                val data = BoundedOutput(); val buffer = ByteArray(8192)
                while (true) { val read = zip.read(buffer); if (read < 0) break; total += read; require(total <= MAX_BYTES) { throw Problem("err_size") }; data.write(buffer,0,read) }
                result += ArchiveEntry(name,data.toByteArray()); zip.closeEntry()
            }
        } } catch (e: java.io.IOException) { throw Problem("err_archive") }
        require(result.isNotEmpty() && result.none { file -> result.any { other -> other !== file && file.name.startsWith(other.name + "/") } }) { throw Problem("err_archive") }
        return result
    }
}
