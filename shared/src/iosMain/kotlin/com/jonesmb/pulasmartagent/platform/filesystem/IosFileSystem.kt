package com.jonesmb.pulasmartagent.platform.filesystem

import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

class IosFileSystem : FileSystem {

    private val fileManager = NSFileManager.defaultManager

    override fun delete(path: String): Boolean =
        fileManager.removeItemAtPath(path, error = null)

    override fun exists(path: String): Boolean =
        fileManager.fileExistsAtPath(path)

    override fun getFileSize(path: String): Long {
        val attrs = fileManager.attributesOfItemAtPath(path, error = null) ?: return 0L
        return (attrs[NSFileSize] as? Long) ?: 0L
    }

    override fun getAvailableStorageBytes(): Long {
        val url = NSURL.fileURLWithPath(NSHomeDirectory())
        val values = url.resourceValuesForKeys(
            listOf(NSURLVolumeAvailableCapacityForImportantUsageKey),
            error = null,
        ) ?: return 0L
        return (values[NSURLVolumeAvailableCapacityForImportantUsageKey] as? Long) ?: 0L
    }
}

