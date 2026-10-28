package com.germanium.shortcuts.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

class WorkspaceFiles(private val root: File) {
    private val mutex = Mutex()
    private fun resolve(folder: String, name: String = ""): File {
        val normalizedFolder = folder.replace('\\', '/').trim('/')
        val normalizedName = name.replace('\\', '/')
        val relative = if (normalizedName.startsWith("$normalizedFolder/") && normalizedFolder.isNotBlank()) normalizedName else listOf(normalizedFolder, normalizedName).filter { it.isNotBlank() }.joinToString("/")
        require(!folder.startsWith('/') && !name.startsWith('/') && !relative.contains(':') && !relative.contains('\u0000') && relative.length <= 500 && relative.split('/').none { it == ".." || it == "." }) { throw Problem("err_path") }
        check(root.isDirectory || root.mkdirs()) { throw Problem("err_io") }
        val candidate = File(root, relative).canonicalFile
        require(candidate.toPath().startsWith(root.canonicalFile.toPath())) { throw Problem("err_path") }
        return candidate
    }
    suspend fun writeBinary(folder: String, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) { mutex.withLock {
        require(name.isNotBlank() && name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") && bytes.size <= 32 * 1024 * 1024) { throw Problem("err_path") }
        val target = resolve(folder, name); require(target != root.canonicalFile) { throw Problem("err_path") }
        atomicWrite(target, bytes)
        root.canonicalFile.toPath().relativize(target.canonicalFile.toPath()).toString().replace('\\', '/')
    } }
    fun imageFile(path: String): File? = runCatching { resolve("", path).takeIf { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") && it.length() <= 32 * 1024 * 1024 } }.getOrNull()
    fun readableFile(path: String): File = resolve("", path).also { require(it.isFile && it.length() <= 32 * 1024 * 1024) { throw Problem("err_size") } }
    suspend fun perform(operation: String, folder: String, name: String, content: String): Value = withContext(Dispatchers.IO) { mutex.withLock {
        val target = resolve(folder, name)
        fun relative(file: File) = root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
        fun read(file: File): String {
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 1048576) { throw Problem("err_size") }; output.write(buffer, 0, count) }; output.toByteArray()
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        when (operation) {
            "create_folder" -> { val directory = resolve(folder); require(directory.isDirectory || directory.mkdirs()) { throw Problem("err_io") }; Value.Text(relative(directory)) }
            "list_files" -> {
                val directory = resolve(folder); require(directory.isDirectory) { throw Problem("err_io") }
                val files = directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }.sortedBy { it.name }
                Value.ListValue(files.map { Value.Text(relative(it)) })
            }
            else -> {
                require(name.isNotBlank() && target != root.canonicalFile) { throw Problem("err_path") }
                when (operation) {
                    "create_file" -> { require(!target.exists()) { throw Problem("err_file_exists") }; require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { throw Problem("err_io") }; Files.createFile(target.toPath()); Value.Text(relative(target)) }
                    "read_file" -> { require(target.isFile) { throw Problem("err_io") }; Value.Text(read(target)) }
                    "write_file", "append_file" -> {
                        val text = (if (operation == "append_file" && target.exists()) read(target) else "") + content
                        val bytes = text.toByteArray(Charsets.UTF_8); require(bytes.size <= 1048576) { throw Problem("err_size") }
                        atomicWrite(target, bytes); Value.Text(relative(target))
                    }
                    "delete_file" -> { require(target.isFile) { throw Problem("err_io") }; Files.delete(target.toPath()); Value.BooleanValue(true) }
                    "file_info" -> { require(target.isFile) { throw Problem("err_io") }; Value.Dictionary(mapOf("name" to Value.Text(target.name), "path" to Value.Text(relative(target)), "size" to Value.Number(target.length().toBigDecimal()), "modified" to Value.Number(target.lastModified().toBigDecimal()))) }
                    else -> throw Problem("err_unknown_action")
                }
            }
        }
    } }
}
