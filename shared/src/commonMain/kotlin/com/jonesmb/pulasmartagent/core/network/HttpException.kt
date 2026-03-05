package com.jonesmb.pulasmartagent.core.network

/** Platform-agnostic HTTP error carrying the response [code]. */
class HttpException(val code: Int) : Exception("HTTP $code")

