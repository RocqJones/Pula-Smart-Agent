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
            var stopReason: SyncStopReason? = null

            if (fileSystem.getAvailableStorageBytes() < StoragePolicy.MIN_REQUIRED_FREE_SPACE_BYTES) {
                return@withContext SyncResult(succeeded, failed, SyncStopReason.LowStorage)
            }

            val pending = repository.getPendingSurveys()

            for (survey in pending) {
                if (!networkMonitor.isConnected()) {
                    failed.add(survey.id)
                    return@withContext SyncResult(succeeded, failed, SyncStopReason.NetworkLost)
                }

                // Phase 1 — upload survey metadata.
                // Returns: true = proceed, false = survey failed (continue to next), null = abort sync.
                val metaOk = uploadMeta(survey, failed, onStop = { stopReason = it })
                if (metaOk == null) return@withContext SyncResult(succeeded, failed, stopReason)
                if (!metaOk) continue

                // Phase 2 — upload attachments sequentially.
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

    // true = ok, false = survey failed (continue), null = abort (stopReason is set via onStop)
    private suspend fun uploadMeta(
        survey: SurveyResponse,
        failed: MutableList<String>,
        onStop: (SyncStopReason) -> Unit,
    ): Boolean? {
        val result = api.uploadSurvey(survey)
        return result.fold(
            onSuccess = { true },
            onFailure = { throwable ->
                val error = throwable.toSyncError()
                repository.markAsFailed(survey.id, error)
                failed.add(survey.id)
                val stop = applyRetryPolicy(survey.id, error)
                if (stop != null) { onStop(stop); null } else false
            }
        )
    }

    // Returns non-null SyncStopReason when the engine must abort; null means continue to next survey.
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
            val stop = result.fold(
                onSuccess = {
                    attachmentRepository.markAsUploaded(attachment.id)
                    if (StoragePolicy.AUTO_DELETE_AFTER_UPLOAD) fileSystem.delete(attachment.localPath)
                    null
                },
                onFailure = { throwable ->
                    val error = throwable.toSyncError()
                    attachmentRepository.markAsFailed(attachment.id, error)
                    val stop = attachmentStopReasonFor(error)
                    when {
                        stop != null -> {
                            repository.markAsFailed(survey.id, error)
                            failed.add(survey.id)
                        }
                        else -> {
                            // ServerError 500+: retriable — record and continue
                            attachmentRepository.incrementRetry(attachment.id)
                        }
                    }
                    stop
                }
            )
            if (stop != null) return stop
        }
        return null
    }

    /**
     * Survey-level retry/stop policy:
     * - IOException / Timeout   - NetworkLost  (stop)
     * - ServerError 400-499     - pin retry to max, continue
     * - ServerError 500+        - increment retry, continue
     * - Unknown / Serialization - FatalError   (stop)
     */
    private suspend fun applyRetryPolicy(id: String, error: SyncError): SyncStopReason? = when (error) {
        SyncError.NoInternet, SyncError.Timeout -> SyncStopReason.NetworkLost
        is SyncError.ServerError if error.code in 400..499 -> { repository.pinRetryToMax(id); null }
        is SyncError.ServerError if error.code >= 500 -> { repository.incrementRetry(id); null }
        else -> SyncStopReason.FatalError
    }

    /**
     * Attachment-level stop policy:
     * - NoInternet / Timeout   - NetworkLost  (stop)
     * - ServerError 400-499    - FatalError   (stop — bad payload, pointless to retry)
     * - ServerError 500+       - null         (retriable, continue)
     * - Unknown / other fatal  - FatalError   (stop)
     */
    private fun attachmentStopReasonFor(error: SyncError): SyncStopReason? = when (error) {
        SyncError.NoInternet, SyncError.Timeout -> SyncStopReason.NetworkLost
        is SyncError.ServerError if error.code in 400..499 -> SyncStopReason.FatalError
        is SyncError.ServerError if error.code >= 500 -> null
        else -> SyncStopReason.FatalError
    }
}