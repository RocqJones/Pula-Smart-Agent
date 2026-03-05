package com.jonesmb.pulasmartagent.data.attachments

import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.repository.AttachmentRepository
import com.jonesmb.pulasmartagent.platform.filesystem.FileSystem

class AttachmentManager(
    private val repository: AttachmentRepository,
    private val fileSystem: FileSystem,
) {
    fun deleteLocalFileIfUploaded(attachment: Attachment) {
        if (attachment.uploadStatus == AttachmentUploadStatus.UPLOADED) {
            fileSystem.delete(attachment.localPath)
        }
    }

    suspend fun cleanupOldUploadedAttachments() {
        repository.getUploadedAttachments()
            .filter { fileSystem.exists(it.localPath) }
            .forEach { fileSystem.delete(it.localPath) }
    }
}

