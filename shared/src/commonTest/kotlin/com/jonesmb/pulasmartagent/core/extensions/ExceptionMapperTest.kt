package com.jonesmb.pulasmartagent.core.extensions

import com.jonesmb.pulasmartagent.core.network.HttpException
import com.jonesmb.pulasmartagent.core.network.SyncErrorException
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExceptionMapperTest {

    @Test
    fun `SyncErrorException unwraps to its typed SyncError`() {
        val error = SyncError.ServerError(503)
        assertEquals(error, SyncErrorException(error).toSyncError())
    }

    @Test
    fun `SerializationError wrapped in SyncErrorException round-trips correctly`() {
        // Serialization failures are caught at the network boundary and re-thrown
        // as SyncErrorException(SyncError.SerializationError) before reaching this mapper.
        val result = SyncErrorException(SyncError.SerializationError).toSyncError()
        assertEquals(SyncError.SerializationError, result)
        assertEquals(false, result.isRetriable)
    }

    @Test
    fun `TimeoutCancellationException maps to Timeout`() = runTest {
        var caught: Throwable? = null
        try {
            withTimeout(100) {
                // suspend forever so the virtual clock fires the timeout
                kotlinx.coroutines.delay(Long.MAX_VALUE)
            }
        } catch (e: TimeoutCancellationException) {
            caught = e
        }
        assertEquals(SyncError.Timeout, caught!!.toSyncError())
    }

    @Test
    fun `HttpException 400 maps to non-retriable ServerError`() {
        val result = HttpException(400).toSyncError()
        assertIs<SyncError.ServerError>(result)
        assertEquals(400, result.code)
        assertEquals(false, result.isRetriable)
    }

    @Test
    fun `HttpException 422 maps to non-retriable ServerError`() {
        val result = HttpException(422).toSyncError()
        assertIs<SyncError.ServerError>(result)
        assertEquals(422, result.code)
        assertEquals(false, result.isRetriable)
    }

    @Test
    fun `HttpException 500 maps to retriable ServerError`() {
        val result = HttpException(500).toSyncError()
        assertIs<SyncError.ServerError>(result)
        assertEquals(500, result.code)
        assertEquals(true, result.isRetriable)
    }

    @Test
    fun `HttpException 503 maps to retriable ServerError`() {
        val result = HttpException(503).toSyncError()
        assertIs<SyncError.ServerError>(result)
        assertEquals(503, result.code)
        assertEquals(true, result.isRetriable)
    }

    @Test
    fun `unknown exception maps to Unknown`() {
        assertEquals(SyncError.Unknown, RuntimeException("unexpected").toSyncError())
    }

    @Test
    fun `NullPointerException maps to Unknown`() {
        assertEquals(SyncError.Unknown, NullPointerException().toSyncError())
    }

    @Test
    fun `NoInternet wrapped in SyncErrorException is retriable`() {
        val result = SyncErrorException(SyncError.NoInternet).toSyncError()
        assertEquals(SyncError.NoInternet, result)
        assertEquals(true, result.isRetriable)
    }
}
