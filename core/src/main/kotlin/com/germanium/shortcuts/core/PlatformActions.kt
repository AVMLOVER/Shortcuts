package com.germanium.shortcuts.core

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean = false)
data class IntentExtra(val key: String, val type: String, val value: String)
data class IntentSpec(
    val action: String = "android.intent.action.VIEW", val data: String = "", val mimeType: String = "",
    val packageName: String = "", val className: String = "", val categories: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
)
interface PlatformActions {
    suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult
    suspend fun launchIntent(spec: IntentSpec)
    suspend fun chooseMenu(title: String, items: List<Pair<String, String>>): String? = error("Menu UI unavailable")
    suspend fun quickLook(value: Value): Unit = error("Quick Look UI unavailable")
    suspend fun createCalendarEvent(event: CalendarEventSpec): String = error("Calendar backend unavailable")
    suspend fun postNotification(title: String, body: String): Unit = error("Notification backend unavailable")
    suspend fun installApk(uri: String, mode: ExecutionMode): String = error("APK installation backend unavailable")
    suspend fun askInput(title: String, prompt: String, initial: String, type: String): String? = throw Problem("err_backend")
    suspend fun chooseMany(title: String, items: List<Pair<String, String>>): List<String>? = chooseMenu(title, items)?.let { listOf(it) }
    suspend fun openApp(packageName: String, className: String) = launchIntent(IntentSpec("android.intent.action.MAIN", packageName = packageName, className = className, categories = listOf("android.intent.category.LAUNCHER")))
    suspend fun clipboard(value: String? = null): String = throw Problem("err_backend")
    suspend fun requestUrl(url: String): String = throw Problem("err_backend")
    suspend fun workspace(operation: String, folder: String, name: String, content: String = ""): Value = throw Problem("err_backend")
    suspend fun deviceInfo(): Value = throw Problem("err_backend")
    suspend fun readCalendarDay(day: CalendarDaySpec): Value = throw Problem("err_backend")
    suspend fun screenshot(folder: String, filename: String): Value = throw Problem("err_backend")
    suspend fun showResult(title: String, body: String, allowCancel: Boolean): Boolean = throw Problem("err_backend")
    suspend fun viewHtml(html: String): Unit = throw Problem("err_backend")
    suspend fun vibrate(milliseconds: Long, amplitude: Int): Unit = throw Problem("err_backend")
    suspend fun shortcutCount(): Int = throw Problem("err_backend")
    suspend fun chooseFile(imageOnly: Boolean): Value = throw Problem("err_backend")
    suspend fun takePhoto(): Value = throw Problem("err_backend")
    suspend fun image(operation: String, source: String, options: Map<String, String>): Value = throw Problem("err_backend")
    suspend fun readBinary(source: String): ByteArray = throw Problem("err_backend")
    suspend fun currentLocation(): Value = throw Problem("err_backend")
    suspend fun fileAction(operation: String, folder: String, name: String, content: Value, mode: ExecutionMode, external: Boolean = false): Value {
        require(!external) { throw Problem("err_permission") }
        val result = workspace(when (operation) { "file_info" -> "read_file"; "file_metadata" -> "file_info"; else -> operation }, folder, name, ValueDocument.encode(content))
        return if (operation in listOf("read_file", "file_info")) ValueDocument.decode(result.text()) else result
    }
    companion object {
        val Unavailable = object : PlatformActions {
            override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult = throw Problem("err_invalid_data")
            override suspend fun launchIntent(spec: IntentSpec): Unit = throw Problem("err_invalid_data")
        }
    }
}
data class CalendarEventSpec(val title: String, val startMillis: Long, val endMillis: Long, val description: String = "", val location: String = "", val calendarId: Long? = null, val reminderMinutes: Int? = null)
data class CalendarDaySpec(val date: java.time.LocalDate, val zone: java.time.ZoneId) {
    val startMillis: Long get() = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis: Long get() = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
fun parseIntentExtras(source: String, render: (String) -> String = { it }): List<IntentExtra> {
    val entries = source.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.split('|', limit = 3)
        require(parts.size == 3 && parts[1].isNotBlank()) { throw Problem("err_invalid_data") }
        val value = render(parts[2])
        when (parts[0].trim()) {
            "string" -> Unit
            "int" -> require(value.toIntOrNull() != null) { throw Problem("err_invalid_data") }
            "long" -> require(value.toLongOrNull() != null) { throw Problem("err_invalid_data") }
            "double" -> require(value.toDoubleOrNull()?.isFinite() == true) { throw Problem("err_invalid_data") }
            "boolean" -> require(value == "true" || value == "false") { throw Problem("err_invalid_data") }
            else -> throw Problem("err_invalid_data")
        }
        IntentExtra(parts[1].trim(), parts[0].trim(), value)
    }
    require(entries.size <= 50 && entries.map { it.key }.distinct().size == entries.size) { throw Problem("err_size") }
    return entries
}
fun intentExtrasFromParameters(parameters: Map<String, String>, render: (String) -> String): List<IntentExtra> {
    val entries = readEntrySpecs(parameters, "extra", "extras")
    require(entries.size <= 50 && entries.all { it.key.isNotBlank() } && entries.map { it.key }.distinct().size == entries.size) { throw Problem("err_size") }
    var totalLength = 0L
    return entries.map { entry ->
        val value = render(entry.value)
        totalLength += entry.key.length + value.length
        require(totalLength <= 131072) { throw Problem("err_size") }
        when (entry.type) {
            "string" -> Unit
            "int" -> require(value.toIntOrNull() != null) { throw Problem("err_invalid_data") }
            "long" -> require(value.toLongOrNull() != null) { throw Problem("err_invalid_data") }
            "double" -> require(value.toDoubleOrNull()?.isFinite() == true) { throw Problem("err_invalid_data") }
            "boolean" -> require(value == "true" || value == "false") { throw Problem("err_invalid_data") }
            else -> throw Problem("err_invalid_data")
        }
        IntentExtra(entry.key, entry.type, value)
    }
}
