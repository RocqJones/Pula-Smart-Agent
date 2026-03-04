package com.jonesmb.pulasmartagent.platform.filesystem

class FakeFileSystem(
    private val availableBytes: Long = Long.MAX_VALUE,
) : FileSystem {

    val deleted = mutableListOf<String>()

    override fun delete(path: String): Boolean {
        deleted.add(path)
        return true
    }

    override fun exists(path: String): Boolean = !deleted.contains(path)

    override fun getFileSize(path: String): Long = 0L

    override fun getAvailableStorageBytes(): Long = availableBytes
}

