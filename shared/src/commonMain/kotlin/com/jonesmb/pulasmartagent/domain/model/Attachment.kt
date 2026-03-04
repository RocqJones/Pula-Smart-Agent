package com.jonesmb.pulasmartagent.domain.model

import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import kotlinx.datetime.Instant

data class Attachment(
    val id: String,
    val surveyId: String,
    val localPath: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val uploadStatus: AttachmentUploadStatus,
    val retryCount: Int,
    val lastError: String?,
)

