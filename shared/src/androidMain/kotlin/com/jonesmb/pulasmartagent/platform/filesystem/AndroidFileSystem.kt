package com.jonesmb.pulasmartagent.platform.filesystem

import android.os.Environment
import android.os.StatFs
import java.io.File

class AndroidFileSystem : FileSystem {

    override fun delete(path: String): Boolean = File(path).delete()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun getFileSize(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else 0L
    }

    override fun getAvailableStorageBytes(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}

