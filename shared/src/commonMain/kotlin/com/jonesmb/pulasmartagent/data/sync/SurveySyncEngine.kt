package com.jonesmb.pulasmartagent.data.sync

import com.jonesmb.pulasmartagent.core.constants.StoragePolicy
import com.jonesmb.pulasmartagent.core.extensions.toSyncError
import com.jonesmb.pulasmartagent.data.network.SurveyApi
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.SyncResult
import com.jonesmb.pulasmartagent.domain.model.SyncStopReason
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
 * */
class SurveySyncEngine(
    private val repository: SurveyRepository,
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
                    stopReason = SyncStopReason.NetworkLost
                    break
                }

                val result = api.uploadSurvey(survey)

                result.fold(
                    onSuccess = {
                        repository.markAsSynced(survey.id)
                        if (StoragePolicy.AUTO_DELETE_AFTER_UPLOAD) {
                            survey.attachments.forEach { fileSystem.delete(it.localPath) }
                        }
                        succeeded.add(survey.id)
                    },
                    onFailure = { throwable ->
                        val error = throwable.toSyncError()

                        when {
                            error == SyncError.NoInternet || error == SyncError.Timeout -> {
                                repository.markAsFailed(survey.id, error)
                                repository.incrementRetry(survey.id)
                                failed.add(survey.id)
                                stopReason = when (error) {
                                    SyncError.NoInternet -> SyncStopReason.NetworkLost
                                    else -> SyncStopReason.FatalError
                                }
                                return@withContext SyncResult(succeeded, failed, stopReason)
                            }
                            error.isRetriable -> {
                                repository.markAsFailed(survey.id, error)
                                repository.incrementRetry(survey.id)
                                failed.add(survey.id)
                            }
                            else -> {
                                repository.markAsFailed(survey.id, error)
                                failed.add(survey.id)
                                stopReason = SyncStopReason.FatalError
                                return@withContext SyncResult(succeeded, failed, stopReason)
                            }
                        }
                    }
                )
            }

            SyncResult(succeeded, failed, stopReason)
        }
    }
}