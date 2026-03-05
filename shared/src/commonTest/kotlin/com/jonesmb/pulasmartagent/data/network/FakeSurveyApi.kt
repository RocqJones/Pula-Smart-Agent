package com.jonesmb.pulasmartagent.data.network

import com.jonesmb.pulasmartagent.core.network.HttpException
import com.jonesmb.pulasmartagent.core.network.SyncErrorException
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse

class FakeSurveyApi(
    private val surveyBehavior: (callCount: Int) -> FakeApiResponse,
    private val attachmentBehavior: (callCount: Int) -> FakeApiResponse = { FakeApiResponse.Success },
) : SurveyApi {

    private var surveyCallCount = 0
    private var attachmentCallCount = 0

    override suspend fun uploadSurvey(response: SurveyResponse): Result<Unit> =
        surveyBehavior(surveyCallCount++).toResult()

    override suspend fun uploadAttachment(attachment: Attachment): Result<Unit> =
        attachmentBehavior(attachmentCallCount++).toResult()
}

sealed class FakeApiResponse {
    data object Success : FakeApiResponse()
    data class ServerError(val code: Int = 500) : FakeApiResponse()
    data object Timeout : FakeApiResponse()
    data object NetworkLost : FakeApiResponse()
    data object UnknownError : FakeApiResponse()
}

private fun FakeApiResponse.toResult(): Result<Unit> = when (this) {
    is FakeApiResponse.Success      -> Result.success(Unit)
    is FakeApiResponse.ServerError  -> Result.failure(HttpException(code))
    is FakeApiResponse.Timeout      -> Result.failure(SyncErrorException(SyncError.Timeout))
    is FakeApiResponse.NetworkLost  -> Result.failure(SyncErrorException(SyncError.NoInternet))
    is FakeApiResponse.UnknownError -> Result.failure(SyncErrorException(SyncError.Unknown))
}