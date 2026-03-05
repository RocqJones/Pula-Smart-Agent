package com.jonesmb.pulasmartagent.domain.model.status

/** Upload lifecycle for a single photo or file attachment. */
enum class AttachmentUploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,
}