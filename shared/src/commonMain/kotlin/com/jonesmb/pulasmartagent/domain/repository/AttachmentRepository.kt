package com.jonesmb.pulasmartagent.domain.repository

import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment

interface AttachmentRepository {
    suspend fun getUploadedAttachments(): List<Attachment>
    suspend fun markAsUploaded(id: String)
    suspend fun markAsFailed(id: String, error: SyncError)
    suspend fun incrementRetry(id: String)
}