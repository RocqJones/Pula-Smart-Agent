package com.jonesmb.pulasmartagent.data.sync

import com.jonesmb.pulasmartagent.data.network.FakeApiResponse
import com.jonesmb.pulasmartagent.data.network.FakeSurveyApi
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.ResponseNode
import com.jonesmb.pulasmartagent.domain.model.SyncStopReason
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import com.jonesmb.pulasmartagent.domain.repository.FakeSurveyRepository
import com.jonesmb.pulasmartagent.platform.filesystem.FakeFileSystem
import com.jonesmb.pulasmartagent.platform.network.FakeNetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurveySyncEngineTest {

    // helpers
    private fun survey(id: String) = SurveyResponse(
        id = id,
        farmerId = "farmer-1",
        createdAt = Instant.parse("2026-03-04T08:00:00Z"),
        status = SyncStatus.PENDING,
        retryCount = 0,
        nodes = emptyList<ResponseNode>(),
        attachments = emptyList<Attachment>(),
    )

    private fun engine(
        surveys: List<SurveyResponse>,
        api: FakeSurveyApi,
        connected: Boolean = true,
        availableStorageBytes: Long = Long.MAX_VALUE,
    ): Triple<SurveySyncEngine, FakeSurveyRepository, FakeFileSystem> {
        val repo = FakeSurveyRepository(surveys.toMutableList())
        val monitor = FakeNetworkMonitor(connected)
        val fs = FakeFileSystem(availableStorageBytes)
        return Triple(SurveySyncEngine(repo, api, monitor, fs, Dispatchers.Unconfined), repo, fs)
    }

    @Test
    fun `all surveys succeed`() = runTest {
        val surveys = (1..5).map { survey("s$it") }
        val (eng, repo, _) = engine(surveys, FakeSurveyApi { FakeApiResponse.Success })

        val result = eng.sync()

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), result.succeededIds)
        assertTrue(result.failedIds.isEmpty())
        assertNull(result.stoppedReason)
        assertEquals(5, repo.synced.size)
        assertTrue(repo.failed.isEmpty())
    }

    @Test
    fun `6th survey fails with 500, first 5 succeed`() = runTest {
        val surveys = (1..8).map { survey("s$it") }
        val api = FakeSurveyApi { call ->
            if (call < 5) FakeApiResponse.Success else FakeApiResponse.ServerError(500)
        }
        val (eng, repo, _) = engine(surveys, api)

        val result = eng.sync()

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), result.succeededIds)
        assertTrue("s6" in result.failedIds)
        assertEquals(5, repo.synced.size)
        assertTrue(repo.failed.any { it.first == "s6" })
        assertTrue(repo.retried.contains("s6"))
    }

    @Test
    fun `timeout on 3rd survey stops early with FatalError`() = runTest {
        val surveys = (1..5).map { survey("s$it") }
        val api = FakeSurveyApi { call ->
            when (call) {
                0, 1 -> FakeApiResponse.Success
                else -> FakeApiResponse.Timeout
            }
        }
        val (eng, repo, _) = engine(surveys, api)

        val result = eng.sync()

        assertEquals(listOf("s1", "s2"), result.succeededIds)
        assertEquals(listOf("s3"), result.failedIds)
        assertEquals(SyncStopReason.FatalError, result.stoppedReason)
        assertEquals(2, repo.synced.size)
        assertTrue(repo.retried.contains("s3"))
    }

    @Test
    fun `empty queue returns empty result with no stop reason`() = runTest {
        val (eng, _, _) = engine(emptyList(), FakeSurveyApi { FakeApiResponse.Success })

        val result = eng.sync()

        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failedIds.isEmpty())
        assertNull(result.stoppedReason)
    }

    @Test
    fun `concurrent sync calls do not double-execute`() = runTest {
        val surveys = (1..4).map { survey("s$it") }
        var uploadCount = 0
        val api = FakeSurveyApi { uploadCount++; FakeApiResponse.Success }
        val (eng, _, _) = engine(surveys, api)

        val first = async { eng.sync() }
        val second = async { eng.sync() }

        val r1 = first.await()
        val r2 = second.await()

        // Total uploads must equal exactly the number of surveys - no survey uploaded twice
        assertEquals(4, uploadCount)

        // One coroutine got all 4, the other saw an empty queue (already processed)
        val combined = r1.succeededIds + r2.succeededIds
        assertEquals(4, combined.size)
        assertTrue(r1.failedIds.isEmpty() && r2.failedIds.isEmpty())
    }

    @Test
    fun `sync stops immediately with LowStorage when available bytes below threshold`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val (eng, repo, _) = engine(
            surveys,
            FakeSurveyApi { FakeApiResponse.Success },
            availableStorageBytes = 10 * 1024 * 1024L, // 10 MB — below StoragePolicy.MIN_REQUIRED_FREE_SPACE_BYTES (100 MB)
        )

        val result = eng.sync()

        assertEquals(SyncStopReason.LowStorage, result.stoppedReason)
        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failedIds.isEmpty())
        assertTrue(repo.synced.isEmpty())
    }
}
