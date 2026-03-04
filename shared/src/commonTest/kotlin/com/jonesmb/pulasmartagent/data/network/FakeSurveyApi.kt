package com.jonesmb.pulasmartagent.data.network

import com.jonesmb.pulasmartagent.data.sync.SyncErrorException
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse

class FakeSurveyApi(
    private val behavior: (callCount: Int) -> FakeApiResponse,
) : SurveyApi {

    private var callCount = 0

    override suspend fun uploadSurvey(response: SurveyResponse): Result<Unit> {
        return when (val outcome = behavior(callCount++)) {
            FakeApiResponse.Success -> Result.success(Unit)
            is FakeApiResponse.ServerError -> Result.failure(
                SyncErrorException(SyncError.ServerError(outcome.code))
            )
            FakeApiResponse.Timeout -> Result.failure(
                SyncErrorException(SyncError.Timeout)
            )
        }
    }
}

sealed class FakeApiResponse {
    data object Success : FakeApiResponse()
    data class ServerError(val code: Int = 500) : FakeApiResponse()
    data object Timeout : FakeApiResponse()
}

