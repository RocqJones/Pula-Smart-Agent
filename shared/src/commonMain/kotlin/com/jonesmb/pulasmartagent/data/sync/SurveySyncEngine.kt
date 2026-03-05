package com.jonesmb.pulasmartagent.data.sync

import com.jonesmb.pulasmartagent.core.constants.StoragePolicy
import com.jonesmb.pulasmartagent.core.extensions.toSyncError
import com.jonesmb.pulasmartagent.data.network.SurveyApi
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.SyncResult
import com.jonesmb.pulasmartagent.domain.model.SyncStopReason
import com.jonesmb.pulasmartagent.domain.repository.AttachmentRepository
import com.jonesmb.pulasmartagent.domain.repository.SurveyRepository
import com.jonesmb.pulasmartagent.platform.filesystem.FileSystem
import com.jonesmb.pulasmartagent.platform.network.NetworkMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Uploads all pending surveys one-by-one, stopping early on network loss or a fatal error, and
 * returning a [SyncResult] that summarizes what succeeded, what failed, and why the run stopped.
 */
class SurveySyncEngine(
    private val repository: SurveyRepository,
    private val attachmentRepository: AttachmentRepository,
    private val api: SurveyApi,
    private val networkMonitor: NetworkMonitor,
    private val fileSystem: FileSystem,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        withContext(dispatcher) {
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            if (fileSystem.getAvailableStorageBytes() < StoragePolicy.MIN_REQUIRED_FREE_SPACE_BYTES) {
                return@withContext SyncResult(succeeded, failed, SyncStopReason.LowStorage)
            }

            val pending = repository.getPendingSurveys()

            for (survey in pending) {
                if (!networkMonitor.isConnected()) {
                    failed.add(survey.id)
                    return@withContext SyncResult(succeeded, failed, SyncStopReason.NetworkLost)
                }

                // Phase 1 — upload survey metadata
                val metaResult = api.uploadSurvey(survey)
                val metaStop = metaResult.fold(
                    onSuccess = { null },
                    onFailure = { throwable ->
                        val error = throwable.toSyncError()
                        repository.markAsFailed(survey.id, error)
                        if (error.isRetriable) repository.incrementRetry(survey.id)
                        failed.add(survey.id)
                        stopReasonFor(error)
                    }
                )
                if (metaStop != null) {
                    return@withContext SyncResult(succeeded, failed, metaStop)
                }

                // Phase 2 — upload attachments sequentially
                val attachStop = uploadAttachments(survey, failed)
                if (attachStop != null) {
                    return@withContext SyncResult(succeeded, failed, attachStop)
                }

                repository.markAsSynced(survey.id)
                succeeded.add(survey.id)
            }

            SyncResult(succeeded, failed, null)
        }
    }

    // Returns a SyncStopReason if the caller should abort the entire sync, null to continue.
    private suspend fun uploadAttachments(
        survey: SurveyResponse,
        failed: MutableList<String>,
    ): SyncStopReason? {
        for (attachment in survey.attachments) {
            if (!networkMonitor.isConnected()) {
                repository.markAsFailed(survey.id, SyncError.NoInternet)
                failed.add(survey.id)
                return SyncStopReason.NetworkLost
            }

            val result = api.uploadAttachment(attachment)
            result.fold(
                onSuccess = {
                    attachmentRepository.markAsUploaded(attachment.id)
                    if (StoragePolicy.AUTO_DELETE_AFTER_UPLOAD) {
                        fileSystem.delete(attachment.localPath)
                    }
                },
                onFailure = { throwable ->
                    val error = throwable.toSyncError()
                    attachmentRepository.markAsFailed(attachment.id, error)
                    if (error.isRetriable) attachmentRepository.incrementRetry(attachment.id)
                    val stop = stopReasonFor(error)
                    if (stop != null) {
                        repository.markAsFailed(survey.id, error)
                        failed.add(survey.id)
                        return stop
                    }
                }
            )
        }
        return null
    }

    private fun stopReasonFor(error: SyncError): SyncStopReason? = when {
        error == SyncError.NoInternet -> SyncStopReason.NetworkLost
        error == SyncError.Timeout    -> SyncStopReason.FatalError
        !error.isRetriable            -> SyncStopReason.FatalError
        else                          -> null
    }
}