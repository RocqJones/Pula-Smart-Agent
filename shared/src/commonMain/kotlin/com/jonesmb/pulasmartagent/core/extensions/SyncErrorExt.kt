package com.jonesmb.pulasmartagent.core.extensions

import com.jonesmb.pulasmartagent.domain.errors.SyncError

fun SyncError.toDbString(): String = when (this) {
    is SyncError.NoInternet -> "NO_INTERNET"
    is SyncError.Timeout -> "TIMEOUT"
    is SyncError.ServerError -> "SERVER_ERROR($code)"
    is SyncError.SerializationError -> "SERIALIZATION_ERROR"
    is SyncError.Unknown -> "UNKNOWN"
}

