package com.jonesmb.pulasmartagent.domain.model

import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus

/**
 * Attachments(photo/files) are uploaded independently of the survey response payload;
 */
data class Attachment(
    val id: String,
    val surveyId: String,
    val localPath: String,
    val uploadStatus: AttachmentUploadStatus,
)

