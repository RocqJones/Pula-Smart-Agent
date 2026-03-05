package com.jonesmb.pulasmartagent.core.network

/** Returns true if this throwable represents an I/O connectivity failure on the current platform. */
expect fun Throwable.isIOException(): Boolean

