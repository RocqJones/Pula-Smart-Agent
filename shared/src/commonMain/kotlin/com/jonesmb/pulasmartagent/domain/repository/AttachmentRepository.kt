package com.jonesmb.pulasmartagent.domain.repository

import com.jonesmb.pulasmartagent.domain.model.Attachment

interface AttachmentRepository {
    suspend fun getUploadedAttachments(): List<Attachment>
}

