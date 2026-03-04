package com.jonesmb.pulasmartagent.domain.model

import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus

/**
 * Attachments(photo/files) are uploaded independently of the survey response payload;

 * @property id           Unique identifier (UUID).
 * @property surveyId     The [SurveyResponse.id] this attachment belongs to.
 * @property localPath    Absolute path to the file on the device (platform filesystem).
 * @property uploadStatus Current position in the upload lifecycle.
 */
data class Attachment(
    val id: String,
    val surveyId: String,
    val localPath: String,
    val uploadStatus: AttachmentUploadStatus,
)

