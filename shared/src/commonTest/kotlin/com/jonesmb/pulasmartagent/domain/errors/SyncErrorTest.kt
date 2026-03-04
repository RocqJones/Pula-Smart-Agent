package com.jonesmb.pulasmartagent.domain.errors

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncErrorTest {

    @Test
    fun `NoInternet is retriable`() = assertTrue(SyncError.NoInternet.isRetriable)

    @Test
    fun `Timeout is retriable`() = assertTrue(SyncError.Timeout.isRetriable)

    @Test
    fun `ServerError 5xx is retriable`() {
        assertTrue(SyncError.ServerError(500).isRetriable)
        assertTrue(SyncError.ServerError(503).isRetriable)
        assertTrue(SyncError.ServerError(599).isRetriable)
    }

    @Test
    fun `ServerError 4xx is not retriable`() {
        assertFalse(SyncError.ServerError(400).isRetriable)
        assertFalse(SyncError.ServerError(401).isRetriable)
        assertFalse(SyncError.ServerError(404).isRetriable)
        assertFalse(SyncError.ServerError(422).isRetriable)
    }

    @Test
    fun `SerializationError is not retriable`() = assertFalse(SyncError.SerializationError.isRetriable)

    @Test
    fun `Unknown is not retriable`() = assertFalse(SyncError.Unknown.isRetriable)

    @Test
    fun `exhaustive when over all variants`() {
        val errors: List<SyncError> = listOf(
            SyncError.NoInternet,
            SyncError.Timeout,
            SyncError.ServerError(503),
            SyncError.SerializationError,
            SyncError.Unknown,
        )
        errors.forEach { error ->
            when (error) {
                is SyncError.NoInternet         -> assertTrue(error.isRetriable)
                is SyncError.Timeout            -> assertTrue(error.isRetriable)
                is SyncError.ServerError        -> assertTrue(error.code in 500..599)
                is SyncError.SerializationError -> assertFalse(error.isRetriable)
                is SyncError.Unknown            -> assertFalse(error.isRetriable)
            }
        }
    }
}

