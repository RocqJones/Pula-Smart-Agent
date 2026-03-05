package com.jonesmb.pulasmartagent.data.network

import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse

interface SurveyApi {
    suspend fun uploadSurvey(response: SurveyResponse): Result<Unit>
    suspend fun uploadAttachment(attachment: Attachment): Result<Unit>
}


