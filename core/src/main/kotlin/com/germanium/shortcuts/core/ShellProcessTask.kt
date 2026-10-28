package com.germanium.shortcuts.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded, interruptible process task shared by standard/root and Shizuku's remote process. */
class ShellProcessTask {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile var liveStdout: String = ""; private set
    @Volatile var liveStderr: String = ""; private set
    fun cancel() { cancelled.set(true); process?.destroyForcibly() }
    fun run(argv: List<String>, timeoutMillis: Long, input: InputStream? = null, onStarted: () -> Unit = {}, onOutput: (String, String) -> Unit = { _, _ -> }): ShellResult {
        require(timeoutMillis in 1..120000)
        if (cancelled.get()) throw CancellationException("Command cancelled")
        val child = ProcessBuilder(argv).start()
        process = child
        val readers = Executors.newFixedThreadPool(3) { runnable -> Thread(runnable, "shortcuts-pipe").apply { isDaemon = true } }
        try {
            onStarted()
            if (cancelled.get()) throw CancellationException("Command cancelled")
            val stdout = readers.submit<Pair<String, Boolean>> { drain(child.inputStream) { liveStdout = it; onOutput(liveStdout, liveStderr) } }
            val stderr = readers.submit<Pair<String, Boolean>> { drain(child.errorStream) { liveStderr = it; onOutput(liveStdout, liveStderr) } }
            val writer = if (input == null) { child.outputStream.close(); null } else readers.submit {
                input.use { source -> child.outputStream.use { sink -> source.copyTo(sink) } }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!child.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) throw CancellationException("Command cancelled")
                if (System.nanoTime() >= deadline) throw TimeoutException("Command timed out")
            }
            if (cancelled.get()) throw CancellationException("Command cancelled")
            val writeResult = runCatching { writer?.get(1, TimeUnit.SECONDS) }
            if (child.exitValue() == 0) writeResult.getOrThrow()
            val out = stdout.get(1, TimeUnit.SECONDS)
            val err = stderr.get(1, TimeUnit.SECONDS)
            return ShellResult(child.exitValue(), out.first, err.first, out.second || err.second)
        } finally {
            child.destroyForcibly()
            readers.shutdownNow()
            runCatching { input?.close() }
            process = null
            // Do not wait indefinitely for descriptors held by detached descendants.
        }
    }
    private fun drain(input: InputStream, updated: (String) -> Unit): Pair<String, Boolean> = input.use {
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var truncated = false
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            val keep = minOf(count, 65536 - collected.size())
            if (keep > 0) collected.write(buffer, 0, keep)
            if (keep > 0) runCatching { updated(collected.toByteArray().toString(Charsets.UTF_8)) }
            if (keep < count) truncated = true
        }
        collected.toByteArray().toString(Charsets.UTF_8) to truncated
    }
}
