package com.germanium.shortcuts

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.time.ZoneId

class AndroidSystemActions(private val context: Context, private val interactions: Interactions, private val foreground: () -> Activity?, private val shizuku: ShizukuShellBackend) {
    private suspend fun permissions(vararg permissions: String) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) return
        interactions.ask(Interaction.Permissions(permissions.toList().toTypedArray()))
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) throw Problem("err_permission")
    }
    suspend fun notify(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33) permissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("shortcuts.actions", context.tr("notification_channel"), NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel("shortcuts.actions").importance == NotificationManager.IMPORTANCE_NONE) throw Problem("err_notification")
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "shortcuts.actions").setSmallIcon(R.drawable.ic_shortcuts_monochrome)
            .setContentTitle(title.take(200)).setContentText(body.take(8192)).setStyle(NotificationCompat.BigTextStyle().bigText(body.take(8192)))
            .setAutoCancel(true).setContentIntent(open).build()
        manager.notify((System.nanoTime() and 0x7fffffff).toInt(), notification)
    }
    suspend fun createEvent(event: CalendarEventSpec): String {
        permissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val calendars = withContext(Dispatchers.IO) {
            val result = mutableListOf<Pair<Long, String>>()
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
                while (cursor.moveToNext()) result += cursor.getLong(0) to cursor.getString(1).orEmpty()
            }; result
        }
        if (calendars.isEmpty()) throw Problem("err_calendar")
        val selected = event.calendarId ?: if (calendars.size == 1) calendars.single().first else {
            interactions.ask(Interaction.Menu(context.tr("calendar_choose"), calendars.map { it.first.toString() to it.second }))?.toLongOrNull() ?: throw CancellationException("Calendar selection cancelled")
        }
        if (calendars.none { it.first == selected }) throw Problem("err_calendar")
        return withContext(Dispatchers.IO) {
            val operations = arrayListOf(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, selected).withValue(CalendarContract.Events.TITLE, event.title.take(1000))
                .withValue(CalendarContract.Events.DTSTART, event.startMillis).withValue(CalendarContract.Events.DTEND, event.endMillis)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id).withValue(CalendarContract.Events.DESCRIPTION, event.description.take(8192))
                .withValue(CalendarContract.Events.EVENT_LOCATION, event.location.take(1000)).withValue(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes != null) 1 else 0).build())
            event.reminderMinutes?.let { operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                .withValue(CalendarContract.Reminders.MINUTES, it).withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build() }
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations).first().uri?.toString() ?: throw Problem("err_calendar")
        }
    }
    suspend fun readCalendar(day: CalendarDaySpec): Value {
        permissions(Manifest.permission.READ_CALENDAR)
        return withContext(Dispatchers.IO) {
            val rows = mutableListOf<Value>()
            // Expand recurring instances, including events spanning midnight. All-day times are UTC dates.
            val utcStart = day.date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val utcEnd = day.date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, minOf(day.startMillis, utcStart)); ContentUris.appendId(this, maxOf(day.endMillis, utcEnd))
            }.build()
            val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID)
            context.contentResolver.query(uri, columns, "${CalendarContract.Instances.VISIBLE}=1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val start = cursor.getLong(2); val end = cursor.getLong(3); val allDay = cursor.getInt(4) != 0
                    val first = if (allDay) utcStart else day.startMillis; val last = if (allDay) utcEnd else day.endMillis
                    if (!(start < last && (end > first || (end == start && start >= first)))) continue
                    require(rows.size < 256) { throw Problem("err_size") }
                    rows += Value.Dictionary(linkedMapOf("id" to Value.Number(cursor.getLong(0).toBigDecimal()), "title" to Value.Text(cursor.getString(1).orEmpty().take(1000)),
                        "start" to Value.Number(start.toBigDecimal()), "end" to Value.Number(end.toBigDecimal()), "allDay" to Value.BooleanValue(allDay),
                        "description" to Value.Text(cursor.getString(5).orEmpty().take(8192)), "location" to Value.Text(cursor.getString(6).orEmpty().take(1000)), "calendarId" to Value.Number(cursor.getLong(7).toBigDecimal())))
                }
            } ?: throw Problem("err_calendar")
            Value.ListValue(rows)
        }
    }
    suspend fun installApk(source: String, mode: ExecutionMode): String {
        val uri = Uri.parse(source)
        try { context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: throw Problem("err_apk") }
        catch (e: SecurityException) { throw Problem("err_apk") }
        if (mode == ExecutionMode.STANDARD) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                interactions.ask(Interaction.Launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))))
                if (!context.packageManager.canRequestPackageInstalls()) throw Problem("err_permission")
            }
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true).apply { clipData = ClipData.newRawUri("APK", uri) }
            val result = interactions.ask(Interaction.Launch(intent))
            if (result == Activity.RESULT_CANCELED.toString()) throw CancellationException("Installation cancelled")
            if (result != Activity.RESULT_OK.toString()) throw Problem("err_install")
            return context.tr("installed")
        }
        val temporary = withContext(Dispatchers.IO) { File.createTempFile("install-", ".apk", context.cacheDir) }
        try {
            withContext(Dispatchers.IO) {
                val sourceStream = context.contentResolver.openInputStream(uri) ?: throw Problem("err_apk")
                sourceStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; total += count; if (total > 1073741824) throw Problem("err_size"); output.write(buffer, 0, count) }
                } }
                if (temporary.length() == 0L) throw Problem("err_apk")
            }
            val result = if (mode == ExecutionMode.SHIZUKU) shizuku.execute("", 120000, temporary)
                else runInterruptible(Dispatchers.IO) { ShellProcessTask().run(listOf("su", "-c", "if [ \"$(id -u)\" != 0 ]; then exit 126; fi\npm install -r -S ${temporary.length()} -"), 120000, FileInputStream(temporary)) }
            (context as ShortcutsApplication).diagnostics.trace("apk_install mode=$mode exit=${result.exitCode} stdout=${result.stdout.take(4096)} stderr=${result.stderr.take(4096)}")
            if (result.exitCode != 0 || result.truncated || result.stdout.lineSequence().none { it.trim() == "Success" }) throw Problem("err_install")
            return context.tr("installed")
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }
}
