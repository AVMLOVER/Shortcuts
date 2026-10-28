package com.germanium.shortcuts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.germanium.shortcuts.core.Problem

fun showInteractionNotification(app: ShortcutsApplication) {
    if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) throw Problem("err_notification")
    val manager = app.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("shortcuts.interaction", app.tr("interaction_required"), NotificationManager.IMPORTANCE_HIGH))
    if (manager.getNotificationChannel("shortcuts.interaction").importance == NotificationManager.IMPORTANCE_NONE) throw Problem("err_notification")
    val open = PendingIntent.getActivity(app, 2002, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(2002, NotificationCompat.Builder(app, "shortcuts.interaction").setSmallIcon(R.drawable.ic_shortcuts_monochrome).setContentTitle(app.tr("interaction_required"))
        .setContentText(app.tr("interaction_body")).setContentIntent(open).setAutoCancel(true).build())
}
