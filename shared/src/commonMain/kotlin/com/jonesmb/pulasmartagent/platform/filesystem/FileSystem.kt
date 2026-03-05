package com.jonesmb.pulasmartagent.platform.filesystem

interface FileSystem {
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
    fun getFileSize(path: String): Long
    fun getAvailableStorageBytes(): Long
}