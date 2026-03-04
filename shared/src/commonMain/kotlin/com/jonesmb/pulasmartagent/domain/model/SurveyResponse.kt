package com.jonesmb.pulasmartagent.domain.model

import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import kotlinx.datetime.Instant

/**
 * This is the root aggregate of our sync engine. Server ready object.
 *
 * Sync lifecycle?
 * PENDING → IN_PROGRESS → SYNCED ↘ FAILED  (retryCount incremented, back to PENDING after back-off)
 */
data class SurveyResponse(
    val id: String,
    val farmerId: String,
    val createdAt: Instant,
    val status: SyncStatus,
    val retryCount: Int,
    val nodes: List<ResponseNode>,
    val attachments: List<Attachment>,
)

