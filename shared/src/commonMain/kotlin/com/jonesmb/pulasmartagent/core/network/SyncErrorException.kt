package com.jonesmb.pulasmartagent.core.network

import com.jonesmb.pulasmartagent.domain.errors.SyncError

/** Bridges a typed [SyncError] into the [Throwable] world so it can be carried inside [Result.failure]. */
class SyncErrorException(val error: SyncError) : Exception(error.toString())

