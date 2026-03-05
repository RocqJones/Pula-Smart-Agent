package com.jonesmb.pulasmartagent.domain.model

data class SyncProgress(
    val current: Int,
    val total: Int,
    val currentSurveyId: String,
) {
    val label: String get() = "Uploading $current of $total"
}

