package com.jonesmb.pulasmartagent.core.extensions

import com.jonesmb.pulasmartagent.core.network.HttpException
import com.jonesmb.pulasmartagent.core.network.SyncErrorException
import com.jonesmb.pulasmartagent.core.network.isIOException
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import kotlinx.coroutines.TimeoutCancellationException

fun Throwable.toSyncError(): SyncError = when {
    this is SyncErrorException -> error
    this is TimeoutCancellationException -> SyncError.Timeout
    isIOException() -> SyncError.NoInternet
    this is HttpException && code in 400..499 -> SyncError.ServerError(code)
    this is HttpException && code >= 500 -> SyncError.ServerError(code)
    else -> SyncError.Unknown
}


