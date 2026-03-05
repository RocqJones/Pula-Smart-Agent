package com.jonesmb.pulasmartagent.domain.repository

import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment

class FakeAttachmentRepository : AttachmentRepository {

    val uploaded = mutableListOf<String>()
    val failed = mutableListOf<Pair<String, SyncError>>()
    val retried = mutableListOf<String>()

    override suspend fun getUploadedAttachments(): List<Attachment> = emptyList()

    override suspend fun markAsUploaded(id: String) {
        uploaded.add(id)
    }

    override suspend fun markAsFailed(id: String, error: SyncError) {
        failed.add(id to error)
    }

    override suspend fun incrementRetry(id: String) {
        retried.add(id)
    }
}

