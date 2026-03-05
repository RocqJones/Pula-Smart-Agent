package com.jonesmb.pulasmartagent.domain.repository

import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse

/**
 *  A use case or sync engine in domain can depend on this(SurveyRepository) without knowing SQLDelight or the network exist
 */
interface SurveyRepository {
    suspend fun saveSurvey(response: SurveyResponse)
    suspend fun getPendingSurveys(): List<SurveyResponse>
    suspend fun markAsSynced(id: String)
    suspend fun markAsFailed(id: String, error: SyncError)
    suspend fun incrementRetry(id: String)
    suspend fun pinRetryToMax(id: String)
}