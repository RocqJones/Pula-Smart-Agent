package com.jonesmb.pulasmartagent.core.network

actual fun Throwable.isIOException(): Boolean = this is java.io.IOException

