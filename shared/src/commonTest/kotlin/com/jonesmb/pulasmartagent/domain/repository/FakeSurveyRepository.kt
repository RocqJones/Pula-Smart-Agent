package com.jonesmb.pulasmartagent.domain.repository

import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus

class FakeSurveyRepository(
    private val surveys: MutableList<SurveyResponse> = mutableListOf(),
) : SurveyRepository {

    val synced = mutableListOf<String>()
    val failed = mutableListOf<Pair<String, SyncError>>()
    val retried = mutableListOf<String>()
    val pinnedRetry = mutableListOf<String>()

    override suspend fun saveSurvey(response: SurveyResponse) {
        surveys.add(response)
    }

    override suspend fun getPendingSurveys(): List<SurveyResponse> =
        surveys.filter { it.status == SyncStatus.PENDING }

    override suspend fun markAsSynced(id: String) {
        synced.add(id)
        updateStatus(id, SyncStatus.SYNCED)
    }

    override suspend fun markAsFailed(id: String, error: SyncError) {
        failed.add(id to error)
        updateStatus(id, SyncStatus.FAILED)
    }

    override suspend fun incrementRetry(id: String) {
        retried.add(id)
    }

    override suspend fun pinRetryToMax(id: String) {
        pinnedRetry.add(id)
    }

    private fun updateStatus(id: String, status: SyncStatus) {
        val index = surveys.indexOfFirst { it.id == id }
        if (index != -1) surveys[index] = surveys[index].copy(status = status)
    }
}

