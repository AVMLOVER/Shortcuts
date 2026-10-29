package com.germanium.shortcuts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object NotificationContents {
    private fun directory(app:ShortcutsApplication)=File(app.cacheDir,"notification-content").also{check(it.isDirectory||it.mkdirs())}
    suspend fun store(app:ShortcutsApplication,manager:NotificationManager,id:Int,title:String,value:Value):PendingIntent=withContext(Dispatchers.IO) {
        val directory=directory(app)
        val active=manager.activeNotifications.filter{it.notification.channelId=="shortcuts.actions"}.sortedBy{it.postTime}
        val retained=active.takeLast(31).map{it.id}.toSet()
        active.dropLast(31).forEach{manager.cancel(it.id)}
        // Recently posted notifications may not yet appear in the system's asynchronous active list.
        val now=System.currentTimeMillis()
        directory.listFiles()?.filter{it.extension=="xml"&&it.nameWithoutExtension.toIntOrNull() !in retained&&now-it.lastModified()>86400000L}?.forEach{it.delete()}
        directory.listFiles()?.filter{it.extension=="xml"}?.sortedBy{it.lastModified()}?.dropLast(63)?.forEach{file->file.nameWithoutExtension.toIntOrNull()?.let(manager::cancel);file.delete()}
        val bytes=ValueDocument.encode(value).toByteArray(Charsets.UTF_8);require(bytes.size<=ValueDocument.MAX_BYTES){throw Problem("err_size")}
        val temporary=File.createTempFile(".pending-",".tmp",directory)
        try {FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()};Files.move(temporary.toPath(),File(directory,"$id.xml").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{temporary.delete()}
        val intent=Intent(app,ContentPreviewActivity::class.java).setData(Uri.parse("shortcuts://notification/$id")).putExtra("contentId",id).putExtra("title",title.take(200))
        PendingIntent.getActivity(app,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    suspend fun load(app:ShortcutsApplication,id:Int):Value=withContext(Dispatchers.IO) {
        require(id>=10000){throw Problem("err_io")}
        val file=File(directory(app),"$id.xml")
        require(file.isFile&&file.length()<=ValueDocument.MAX_BYTES){throw Problem("err_io")}
        ValueDocument.decode(file.inputStream().use{it.readBytesBounded(ValueDocument.MAX_BYTES)}.toString(Charsets.UTF_8))
    }
}
