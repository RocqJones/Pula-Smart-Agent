package com.jonesmb.pulasmartagent.domain.model

sealed class SyncStopReason {
    data object NetworkLost : SyncStopReason()
    data object LowStorage : SyncStopReason()
    data object FatalError : SyncStopReason()
    data object None : SyncStopReason()
}

data class SyncResult(
    val succeededIds: List<String>,
    val failedIds: List<String>,
    val stoppedReason: SyncStopReason?,
)

