package com.jonesmb.pulasmartagent.core.extensions

import com.jonesmb.pulasmartagent.core.network.HttpException
import com.jonesmb.pulasmartagent.core.network.SyncErrorException
import com.jonesmb.pulasmartagent.core.network.isIOException
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Maps any throwable to a typed [SyncError].
 *
 * Serialization failures should be caught at the network boundary and re-thrown as
 * SyncErrorException(SyncError.SerializationError) before reaching this mapper,
 * keeping the shared module free of a kotlinx-serialization dependency.
 */
fun Throwable.toSyncError(): SyncError = when {
    this is SyncErrorException -> error
    this is TimeoutCancellationException -> SyncError.Timeout
    isIOException() -> SyncError.NoInternet
    this is HttpException && code in 400..499 -> SyncError.ServerError(code)
    this is HttpException && code >= 500 -> SyncError.ServerError(code)
    else -> SyncError.Unknown
}