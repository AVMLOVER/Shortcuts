package com.germanium.shortcuts

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

class AndroidActions(context: Context, private val interactions: Interactions, private val foregroundActivity: () -> Activity?) : PlatformActions {
    private val context = context.applicationContext
    private val shizuku = ShizukuShellBackend(this.context)
    private val system = AndroidSystemActions(this.context, interactions, foregroundActivity, shizuku)
    private val images = AndroidImages(this.context as ShortcutsApplication)
    private val extras = AndroidExtras104(this.context as ShortcutsApplication)
    override suspend fun power(reboot: Boolean, mode: ExecutionMode, target: String) {
        require(mode == ExecutionMode.ROOT) { throw Problem("err_root_required") }
        val command = PowerTargets.command(reboot,target)
        val granted = try { rawShell("id -u",ExecutionMode.ROOT,5000).let { it.exitCode == 0 && !it.truncated && it.stdout.trim() == "0" } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        require(granted) { throw Problem("err_root_required") }
        val result = rawShell(command,ExecutionMode.ROOT,10000)
        require(result.exitCode == 0) { throw Problem("err_power") }
    }
    override suspend fun openUrl(url: String) {
        val uri = Uri.parse(url)
        val browser = if (uri.scheme?.lowercase() in listOf("http","https")) {
            val generic = Intent(Intent.ACTION_VIEW,Uri.parse("http://example.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
            context.packageManager.resolveActivity(generic,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeUnless { it == "android" || it == context.packageName }
        } else null
        launchIntent(IntentSpec(data=url,packageName=browser.orEmpty(),categories=listOf(Intent.CATEGORY_BROWSABLE)))
    }
    override suspend fun generateQr(text: String, size: Int, correction: String) = extras.generateQr(text,size,correction)
    override suspend fun readBarcode(source: String) = extras.readBarcode(source)
    override suspend fun speak(text: String, language: String, rate: Float, pitch: Float) = extras.speak(text,language,rate,pitch)
    override suspend fun volume(percent: Int?) = extras.volume(percent)
    override suspend fun flashlight(enabled: Boolean) = extras.flashlight(enabled)
    override suspend fun writePartition(source: String, target: String, mode: ExecutionMode) = AndroidPartitionWriter(context as ShortcutsApplication) { command, timeout -> rawShell(command,ExecutionMode.ROOT,timeout) }.write(source,target,mode)
    override suspend fun archive(source: Value, folder: String, filename: String, extract: Boolean): Value = withContext(Dispatchers.IO) {
        val app = context as ShortcutsApplication; val content = AndroidContent(app)
        val references = content.references(source,"files",256)
        require(references.isNotEmpty() && references.size <= 256) { throw Problem("err_archive") }
        if (extract) {
            require(references.size == 1) { throw Problem("err_archive") }
            app.workspace.importArchive(folder,ZipValues.unpack(images.bytes(references.single())))
        } else {
            var total = 0L; val names = mutableSetOf<String>()
            val entries = references.map { ref ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val bytes = images.bytes(ref); total += bytes.size; require(total <= ZipValues.MAX_BYTES) { throw Problem("err_size") }
                val original = (if (ref.startsWith("content://")) content.name(Uri.parse(ref)) else java.io.File(ref).name).replace(Regex("[\\\\/:\\p{Cntrl}]"),"_").ifBlank { "file" }
                var name = original; var index = 2; while (!names.add(name)) { name = "${index++}-$original" }
                ArchiveEntry(name,bytes)
            }
            val name = filename.ifBlank { "archive-${java.util.UUID.randomUUID()}.zip" }; require(name.endsWith(".zip",true)) { throw Problem("err_path") }
            Value.Text(app.workspace.importBinary(folder,name,ZipValues.pack(entries)))
        }
    }
    override suspend fun readBinary(source: String) = images.bytes(source)
    override suspend fun convertInput(kind: String, value: Value) = AndroidContent(context as ShortcutsApplication).extract(kind, value)
    override suspend fun transformImage(operation: String, source: Value, options: Map<String, String>) = if (operation in listOf("text_image", "make_pdf")) AndroidImageDocuments(context as ShortcutsApplication).perform(operation, source, options) else AndroidImageTransforms(context as ShortcutsApplication).perform(operation, source, options)
    override suspend fun share(value: Value) { interactions.ask(Interaction.Launch(AndroidContent(context as ShortcutsApplication).shareIntent(value))) }
    override suspend fun waitForReturn() {
        val app = context as ShortcutsApplication
        if (!app.externalReturn.await() && foregroundActivity() == null) interactions.ask(Interaction.Foreground())
    }
    override suspend fun batteryLevel(): Int {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: throw Problem("err_backend")
        val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        require(level >= 0 && scale > 0) { throw Problem("err_backend") }
        return (level.toLong() * 100 / scale).toInt().coerceIn(0, 100)
    }
    override suspend fun currentLocation(): Value = AndroidLocation(context, interactions).current()
    override suspend fun chooseFile(imageOnly: Boolean): Value {
        val uri = interactions.ask(Interaction.PickFile(imageOnly)) ?: throw kotlinx.coroutines.CancellationException("File selection cancelled")
        return images.fileValue(uri, imageOnly)
    }
    override suspend fun image(operation: String, source: String, options: Map<String, String>) = images.perform(operation, source, options)
    override suspend fun takePhoto(): Value {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            interactions.ask(Interaction.Permissions(arrayOf(android.Manifest.permission.CAMERA)))
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw Problem("err_permission")
        }
        val uri = interactions.ask(Interaction.TakePhoto()) ?: throw kotlinx.coroutines.CancellationException("Photo cancelled")
        try {
            val bytes = images.bytes(uri)
            val path = (context as ShortcutsApplication).workspace.writeBinary("Photos", "photo-${java.util.UUID.randomUUID()}.jpg", bytes)
            return Value.Text(path)
        } finally { if (Uri.parse(uri).authority == "${context.packageName}.files") runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } }
    }
    override suspend fun chooseMenu(title: String, items: List<Pair<String, String>>): String? = interactions.ask(Interaction.Menu(title, items))
    override suspend fun quickLook(value: Value) { val app = context as ShortcutsApplication; if (app.runs.state.value.fromWidget) app.runs.widgetPreview(value); interactions.ask(Interaction.QuickLook(value)) }
    override suspend fun askInput(title: String, prompt: String, initial: String, type: String) = interactions.ask(Interaction.Input(title, prompt, initial, type))
    override suspend fun chooseMany(title: String, items: List<Pair<String, String>>) = interactions.ask(Interaction.Checklist(title, items))?.let { if (it.isEmpty()) emptyList() else it.split(',') }
    override suspend fun workspace(operation: String, folder: String, name: String, content: String) = (this.context as ShortcutsApplication).workspace.perform(operation, folder, name, content)
    override suspend fun clipboard(value: String?): String = withContext(Dispatchers.Main.immediate) {
        if (foregroundActivity() == null) interactions.ask(Interaction.Foreground())
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        if (value != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Shortcuts", value)); value }
        else clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }
    override suspend fun deviceInfo(): Value {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        return Value.Dictionary(mapOf("manufacturer" to Value.Text(android.os.Build.MANUFACTURER), "model" to Value.Text(android.os.Build.MODEL), "sdk" to Value.Number(android.os.Build.VERSION.SDK_INT.toBigDecimal()), "battery" to Value.Number((level * 100 / scale).toBigDecimal())))
    }
    override suspend fun requestUrl(url: String) = requestHttp(HttpRequestSpec(url)).let { require(it.status in 200..299) { throw Problem("err_http_status") }; it.text() }
    override suspend fun requestHttp(request: HttpRequestSpec) = AndroidHttpClient().request(request)
    override suspend fun createCalendarEvent(event: CalendarEventSpec) = system.createEvent(event)
    override suspend fun showResult(title: String, body: String, allowCancel: Boolean) = showResult(title, Value.Text(body), allowCancel)
    override suspend fun showResult(title: String, body: Value, allowCancel: Boolean) = interactions.ask(Interaction.ResultDialog(title, body, allowCancel)) == "ok"
    override suspend fun viewHtml(html: String) { interactions.ask(Interaction.Html(html)) }
    override suspend fun shortcutsForCall() = (context.applicationContext as ShortcutsApplication).shortcuts.list()
    override suspend fun chooseShortcut(items: List<Shortcut>) = interactions.ask(Interaction.Menu(context.tr("choose_shortcut"), items.map { it.uuid to it.name }))
    override suspend fun applicationList(includeSystem: Boolean, format: String, offset: Int, limit: Int): Value {
        val apps = AppsRepository.load(context, allPackages = true).filter { includeSystem || !it.system }.distinctBy { it.packageName }.sortedBy { it.packageName }
        return Value.ListValue(apps.drop(offset).take(limit).map { Value.Text(when (format) { "label" -> it.label; "both" -> "${it.label} (${it.packageName})"; else -> it.packageName }) })
    }
    override suspend fun shortcutCount() = (context as ShortcutsApplication).shortcuts.list().size
    override suspend fun vibrate(milliseconds: Long, amplitude: Int) = withContext(Dispatchers.Main.immediate) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator else { @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java) }
        if (milliseconds > 0 && vibrator.hasVibrator()) vibrator.vibrate(android.os.VibrationEffect.createOneShot(milliseconds, if (vibrator.hasAmplitudeControl()) amplitude else android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
    override suspend fun readCalendarDay(day: CalendarDaySpec) = system.readCalendar(day)
    override suspend fun screenshot(folder: String, filename: String): Value {
        if (android.os.Build.VERSION.SDK_INT <= 28 && !MediaPermissions.granted(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            interactions.ask(Interaction.Permissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)))
            if (!MediaPermissions.granted(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) throw Problem("err_permission")
        }
        val result = interactions.ask(Interaction.Capture(folder, filename)) ?: throw kotlinx.coroutines.CancellationException("Capture cancelled")
        return Value.Text(result)
    }
    override suspend fun postNotification(title: String, body: String) = system.notify(title, Value.Text(body))
    override suspend fun postNotification(title: String, body: Value) = system.notify(title, body)
    override suspend fun installApk(uri: String, mode: ExecutionMode) = system.installApk(uri, mode)
    override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult {
        val assessment = ShellPolicy.assess(command)
        if (assessment.denied) throw Problem("err_shell_denied")
        val app = context as ShortcutsApplication
        val preview = rawShell("pwd; ls -la", mode, 5000)
        require(preview.exitCode == 0 && !preview.truncated) { throw Problem("err_path") }
        if (assessment.needsConfirmation && !showResult(context.tr(if (assessment.deleting) "shell_delete_warning" else "shell_write_warning"), preview.stdout.take(8192) + "\n\n" + command, true)) throw kotlinx.coroutines.CancellationException("Command cancelled")
        return rawShell(command, mode, timeoutMillis) { out, err -> app.runs.shellOutput(out, err) }
    }
    private suspend fun rawShell(command: String, mode: ExecutionMode, timeoutMillis: Long, input: java.io.InputStream? = null, output: (String, String) -> Unit = { _, _ -> }): ShellResult {
        val directory = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace")
        withContext(Dispatchers.IO) { check(directory.isDirectory || directory.mkdirs()) { throw Problem("err_io") } }
        val body = "cd ${ShellPolicy.quote(directory.absolutePath)} || exit 125\n$command"
        if (mode == ExecutionMode.SHIZUKU) return shizuku.execute(body, timeoutMillis, onOutput = output)
        val argv = if (mode == ExecutionMode.ROOT) listOf("su", "-c", "if [ \"$(id -u)\" != 0 ]; then echo 'Root required' >&2; exit 126; fi\n$body") else listOf("/system/bin/sh", "-c", body)
        return runInterruptible(Dispatchers.IO) { ShellProcessTask(maxOf(120000,timeoutMillis)).run(argv, timeoutMillis, input, onOutput = output) }
    }
    suspend fun savedWifiNetworks(mode: ExecutionMode): List<String> {
        val ordinary = AndroidWifi.configured(context) + listOf(AndroidWifi.current(context)).filter { it.isNotBlank() }
        if (mode == ExecutionMode.STANDARD) return ordinary.distinct()
        val result = rawShell("cmd wifi list-networks", mode, 5000)
        if (result.exitCode != 0 || result.truncated) return ordinary.distinct()
        val entries = result.stdout.lineSequence().mapNotNull { line -> Regex("^\\s*[0-9]+\\s+(.+?)\\s{2,}\\S.*$").matchEntire(line)?.groupValues?.get(1)?.let(AndroidWifi::clean) }.filter { it.isNotBlank() }.toList()
        return (ordinary + entries).distinct()
    }
    override suspend fun fileAction(operation: String, folder: String, name: String, content: Value, mode: ExecutionMode, external: Boolean): Value {
        if (!external) return super.fileAction(operation, folder, name, content, mode, false)
        require(mode == ExecutionMode.ROOT) { throw Problem("err_permission") }
        require(folder.startsWith('/') && folder.length <= 500 && !folder.contains('\u0000') && !name.contains('\u0000') && !name.startsWith('/') && (folder + "/" + name).split('/').none { it == ".." || it == "." }) { throw Problem("err_path") }
        val target = folder.trimEnd('/') + if (name.isBlank()) "" else "/$name"
        val path = ShellPolicy.quote(target); val parent = ShellPolicy.quote(java.io.File(target).parent ?: "/")
        val mutating = operation in listOf("create_folder", "create_file", "write_file", "append_file", "delete_file")
        if (mutating) {
            require(target != "/" && name.isNotBlank() || operation == "create_folder" && target != "/") { throw Problem("err_path") }
            val preview = rawShell("ls -ld -- $parent", mode, 5000)
            if (!showResult(context.tr(if (operation == "delete_file") "shell_delete_warning" else "shell_write_warning"), "$target\n${preview.stdout}\n$operation", true)) throw kotlinx.coroutines.CancellationException("File operation cancelled")
        }
        var payload: ByteArray? = null
        val command = when (operation) {
            "read_file", "file_info" -> "test -f $path && test \"\$(stat -c %s -- $path)\" -le 1048576 && cat -- $path"
            "create_folder" -> "mkdir -p -- $path"
            "create_file" -> "test ! -e $path && mkdir -p -- $parent && (set -C; : > $path)"
            "write_file", "append_file" -> {
                val bytes = ValueDocument.encode(content).toByteArray(Charsets.UTF_8); require(bytes.size <= XmlCodec.MAX_BYTES) { throw Problem("err_size") }
                payload = bytes
                "test ! -b $path && test ! -c $path && { test ! -e $path || test -f $path; } && mkdir -p -- $parent && cat ${if (operation == "append_file") ">>" else ">"} $path"
            }
            "delete_file" -> "test -f $path && rm -- $path"
            "list_files" -> "find ${ShellPolicy.quote(folder)} -maxdepth 1 -type f"
            "file_metadata" -> "stat -c '%s|%Y' -- $path"
            else -> throw Problem("err_unknown_action")
        }
        val result = rawShell(command, mode, 10000, input = payload?.inputStream())
        require(result.exitCode == 0 && !result.truncated) { throw Problem("err_io") }
        return when (operation) {
            "read_file", "file_info" -> ValueDocument.decode(result.stdout)
            "list_files" -> Value.ListValue(result.stdout.lineSequence().filter { it.isNotBlank() }.map { Value.Text(it) }.toList())
            "delete_file" -> Value.BooleanValue(true)
            "file_metadata" -> { val fields = result.stdout.trim().split('|'); Value.Dictionary(mapOf("path" to Value.Text(target), "size" to Value.Number(parseNumber(fields[0])), "modified" to Value.Number(parseNumber(fields[1]).multiply(1000.toBigDecimal())))) }
            else -> Value.Text(target)
        }
    }
    override suspend fun launchIntent(spec: IntentSpec): Unit = withContext(Dispatchers.Main.immediate) {
        if (foregroundActivity() == null) interactions.ask(Interaction.Foreground())
        val activity = foregroundActivity() ?: throw Problem("err_foreground")
        require(spec.action.isNotBlank()) { throw Problem("err_invalid_data") }
        require(spec.className.isBlank() || spec.packageName.isNotBlank()) { throw Problem("err_invalid_data") }
        val intent = Intent(spec.action).apply {
            if (spec.data.isNotBlank() || spec.mimeType.isNotBlank()) setDataAndType(spec.data.takeIf(String::isNotBlank)?.let(Uri::parse), spec.mimeType.takeIf(String::isNotBlank))
            if (spec.className.isNotBlank()) component = ComponentName(spec.packageName, if (spec.className.startsWith('.')) spec.packageName + spec.className else spec.className)
            else if (spec.packageName.isNotBlank()) setPackage(spec.packageName)
            spec.categories.forEach(::addCategory)
            spec.extras.forEach { extra -> when (extra.type) {
                "string" -> putExtra(extra.key, extra.value)
                "int" -> putExtra(extra.key, extra.value.toInt())
                "long" -> putExtra(extra.key, extra.value.toLong())
                "double" -> putExtra(extra.key, extra.value.toDouble())
                "boolean" -> putExtra(extra.key, extra.value.toBooleanStrict())
            } }
        }
        val app = context as ShortcutsApplication
        val returnToken = app.externalReturn.arm()
        try { activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) { app.externalReturn.cancel(returnToken); throw Problem("err_no_handler")
        } catch (e: SecurityException) { app.externalReturn.cancel(returnToken); throw Problem("err_permission") }
    }
}
