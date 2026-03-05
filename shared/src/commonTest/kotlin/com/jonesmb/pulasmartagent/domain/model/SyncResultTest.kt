package com.jonesmb.pulasmartagent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncResultTest {

    @Test
    fun `all succeeded, no stop reason`() {
        val result = SyncResult(
            succeededIds = listOf("r1", "r2"),
            failedIds = emptyList(),
            stoppedReason = null,
        )
        assertEquals(2, result.succeededIds.size)
        assertTrue(result.failedIds.isEmpty())
        assertNull(result.stoppedReason)
    }

    @Test
    fun `partial sync stopped by NetworkLost`() {
        val result = SyncResult(
            succeededIds = listOf("r1"),
            failedIds = listOf("r2", "r3"),
            stoppedReason = SyncStopReason.NetworkLost,
        )
        assertEquals(1, result.succeededIds.size)
        assertEquals(2, result.failedIds.size)
        assertEquals(SyncStopReason.NetworkLost, result.stoppedReason)
    }

    @Test
    fun `stopped by FatalError`() {
        val result = SyncResult(
            succeededIds = emptyList(),
            failedIds = listOf("r1"),
            stoppedReason = SyncStopReason.FatalError,
        )
        assertEquals(SyncStopReason.FatalError, result.stoppedReason)
    }

    @Test
    fun `SyncStopReason exhaustive when`() {
        val reasons: List<SyncStopReason> = listOf(
            SyncStopReason.NetworkLost,
            SyncStopReason.LowStorage,
            SyncStopReason.FatalError,
            SyncStopReason.None,
        )
        reasons.forEach { reason ->
            when (reason) {
                is SyncStopReason.NetworkLost -> assertEquals(SyncStopReason.NetworkLost, reason)
                is SyncStopReason.LowStorage  -> assertEquals(SyncStopReason.LowStorage, reason)
                is SyncStopReason.FatalError  -> assertEquals(SyncStopReason.FatalError, reason)
                is SyncStopReason.None        -> assertEquals(SyncStopReason.None, reason)
            }
        }
    }
}

