package com.jonesmb.pulasmartagent.platform.filesystem

import android.content.Context
import java.io.File

class AndroidFileSystem(private val context: Context) : FileSystem {

    override fun delete(path: String): Boolean =
        runCatching { File(path).delete() }.getOrDefault(false)

    override fun exists(path: String): Boolean = File(path).exists()

    override fun getFileSize(path: String): Long = File(path).length()

    override fun getAvailableStorageBytes(): Long = context.filesDir.usableSpace
}
