package com.jonesmb.pulasmartagent.domain.model

import kotlinx.datetime.Instant

data class SyncAttemptLog(
    val surveyId: String,
    val attemptedAt: Instant,
    val httpStatusCode: Int?,
    val exceptionType: String?,
    val retryCountAtAttempt: Int,
    val stopReason: String?,
)

data class DeviceContext(
    val androidApiLevel: Int?,
    val availableStorageBytes: Long,
    val networkType: String,
    val batteryPercent: Int?,
)

data class DiagnosticsReport(
    val generatedAt: Instant,
    val deviceContext: DeviceContext,
    val syncLogs: List<SyncAttemptLog>,
    val pendingSurveyCount: Int,
    val failedSurveyCount: Int,
)

