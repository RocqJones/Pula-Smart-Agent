package com.jonesmb.pulasmartagent.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.jonesmb.pulasmartagent.db.SmartAgentDatabase
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.repository.AttachmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class AttachmentRepositoryImpl(driver: SqlDriver) : AttachmentRepository {

    private val queries = SmartAgentDatabase(driver).attachmentQueries

    override suspend fun getUploadedAttachments(): List<Attachment> =
        withContext(Dispatchers.Default) {
            queries.selectUploaded().executeAsList().map { row ->
                Attachment(
                    id = row.id,
                    surveyId = row.survey_id,
                    localPath = row.local_path,
                    sizeBytes = row.size_bytes,
                    createdAt = Instant.fromEpochMilliseconds(row.created_at),
                    uploadStatus = AttachmentUploadStatus.valueOf(row.upload_status),
                    retryCount = row.retry_count.toInt(),
                    lastError = row.last_error,
                )
            }
        }

    override suspend fun markAsUploaded(id: String): Unit = withContext(Dispatchers.Default) {
        queries.markAsUploaded(id)
    }

    override suspend fun markAsFailed(id: String, error: SyncError): Unit = withContext(Dispatchers.Default) {
        queries.markAsFailed(last_error = error.toString(), id = id)
    }

    override suspend fun incrementRetry(id: String): Unit = withContext(Dispatchers.Default) {
        queries.incrementRetry(id)
    }
}