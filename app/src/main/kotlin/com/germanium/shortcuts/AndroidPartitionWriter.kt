package com.germanium.shortcuts

import android.net.Uri
import android.os.StatFs
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Root-only raw-image write. Never invoked by the device regression suite. */
internal class AndroidPartitionWriter(private val app: ShortcutsApplication, private val root: suspend (String,Long)->ShellResult) {
    private fun name(source: String): String = if (source.startsWith("content://")) AndroidContent(app).name(Uri.parse(source)) else File(source).name
    suspend fun write(source: String, target: String, mode: ExecutionMode) {
        require(mode == ExecutionMode.ROOT) { throw Problem("err_root_required") }
        PartitionWrites.target(target)
        val original = name(source); PartitionWrites.imageName(original)
        val access = try { root("id -u",5000) } catch (e: CancellationException) { throw e } catch (_: Exception) { throw Problem("err_root_required") }
        require(access.exitCode == 0 && !access.truncated && access.stdout.trim() == "0") { throw Problem("err_root_required") }
        val canonical = root("readlink -f -- ${ShellPolicy.quote(target)}",5000).let { require(it.exitCode == 0 && !it.truncated) { throw Problem("err_partition_target") }; it.stdout.trim() }
        PartitionWrites.target(canonical)
        val quoted = ShellPolicy.quote(canonical)
        val capacity = root("test -b $quoted && blockdev --getsize64 $quoted",5000).let { require(it.exitCode == 0 && !it.truncated) { throw Problem("err_partition_target") }; it.stdout.trim().toLongOrNull()?.takeIf { size -> size > 0 } ?: throw Problem("err_partition_target") }
        val directory = File(app.cacheDir,"partition-write"); require(directory.isDirectory || directory.mkdirs()) { throw Problem("err_io") }
        val staged = File(directory,"${UUID.randomUUID()}.img")
        try {
            val size = withContext(Dispatchers.IO) {
                val input = if (source.startsWith("content://")) app.contentResolver.openInputStream(Uri.parse(source)) ?: throw Problem("err_io") else app.workspace.streamFile(source).inputStream()
                var copied = 0L
                input.use { from -> staged.outputStream().use { to ->
                    val buffer = ByteArray(1024*1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); val read=from.read(buffer); if(read<0)break
                        require(read.toLong() <= capacity-copied) { throw Problem("err_partition_size") }
                        require(StatFs(directory.absolutePath).availableBytes > read+16L*1024*1024) { throw Problem("err_io") }
                        to.write(buffer,0,read);copied+=read
                    }
                } }
                require(copied > 0) { throw Problem("err_partition_size") }
                staged.inputStream().use { raw -> val header=ByteArray(4); val n=raw.read(header); require(!(n==4 && header.contentEquals(byteArrayOf(0x3a,0xff.toByte(),0x26,0xed.toByte())))) { throw Problem("err_partition_sparse") } }
                copied
            }
            val accepted = app.actions.showResult(app.tr("partition_warning_title"),app.tr("partition_warning_body",original,target,canonical,size.toString(),capacity.toString()),true)
            if (!accepted) throw CancellationException("Partition write cancelled")
            currentCoroutineContext().ensureActive()
            // Recheck the block device and capacity immediately before the write.
            val command = "test -b $quoted && test \"\$(blockdev --getsize64 $quoted)\" -ge $size && dd if=${ShellPolicy.quote(staged.absolutePath)} of=$quoted bs=1048576 conv=fsync && sync"
            val result = root(command,600000)
            require(result.exitCode == 0 && !result.truncated) { throw Problem("err_partition_write") }
        } finally { withContext(NonCancellable + Dispatchers.IO) { staged.delete() } }
    }
}
