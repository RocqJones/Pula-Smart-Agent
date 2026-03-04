package com.jonesmb.pulasmartagent.domain.errors

sealed class SyncError(val isRetriable: Boolean) {
    data object NoInternet : SyncError(isRetriable = true)
    data object Timeout : SyncError(isRetriable = true)
    data class ServerError(val code: Int) : SyncError(isRetriable = code in 500..599)
    data object SerializationError : SyncError(isRetriable = false)
    data object Unknown : SyncError(isRetriable = false)
}