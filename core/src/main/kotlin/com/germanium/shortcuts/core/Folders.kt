package com.germanium.shortcuts.core

import java.io.File
import java.util.UUID

data class ShortcutFolder(val id: String = UUID.randomUUID().toString(), val name: String, val icon: String = "folder", val color: String = "purple") {
    init { Shortcut(id = id, name = name, icon = icon, color = color) }
}
/** Folder metadata uses the same bounded, atomic XML codec in its own directory. */
class FolderRepository(directory: File) {
    private val documents = XmlShortcutRepository(directory)
    suspend fun list() = documents.list().map { ShortcutFolder(it.id, it.name, it.icon, it.color) }
    suspend fun save(folder: ShortcutFolder) = documents.save(Shortcut(folder.id, folder.name, icon = folder.icon, color = folder.color))
    suspend fun delete(id: String) = documents.delete(id)
}
