package com.jonesmb.pulasmartagent.data.sync

import com.jonesmb.pulasmartagent.data.network.FakeApiResponse
import com.jonesmb.pulasmartagent.data.network.FakeSurveyApi
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.ResponseNode
import com.jonesmb.pulasmartagent.domain.model.SyncStopReason
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import com.jonesmb.pulasmartagent.domain.repository.FakeAttachmentRepository
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

    private fun attachment(id: String, surveyId: String = "s1") = Attachment(
        id = id,
        surveyId = surveyId,
        localPath = "/data/$id.jpg",
        sizeBytes = 1024L,
        createdAt = Instant.parse("2026-03-04T08:00:00Z"),
        uploadStatus = AttachmentUploadStatus.PENDING,
        retryCount = 0,
        lastError = null,
    )

    private fun survey(id: String, attachments: List<Attachment> = emptyList()) = SurveyResponse(
        id = id,
        farmerId = "farmer-1",
        createdAt = Instant.parse("2026-03-04T08:00:00Z"),
        status = SyncStatus.PENDING,
        retryCount = 0,
        nodes = emptyList<ResponseNode>(),
        attachments = attachments,
    )

    private data class Harness(
        val engine: SurveySyncEngine,
        val repo: FakeSurveyRepository,
        val attachmentRepo: FakeAttachmentRepository,
        val fs: FakeFileSystem,
    )

    private fun engine(
        surveys: List<SurveyResponse>,
        api: FakeSurveyApi,
        connected: Boolean = true,
        availableStorageBytes: Long = Long.MAX_VALUE,
    ): Harness {
        val repo = FakeSurveyRepository(surveys.toMutableList())
        val attachmentRepo = FakeAttachmentRepository()
        val monitor = FakeNetworkMonitor(connected)
        val fs = FakeFileSystem(availableStorageBytes)
        return Harness(
            SurveySyncEngine(repo, attachmentRepo, api, monitor, fs, Dispatchers.Unconfined),
            repo,
            attachmentRepo,
            fs,
        )
    }

    @Test
    fun `all surveys succeed`() = runTest {
        val surveys = (1..5).map { survey("s$it") }
        val h = engine(surveys, FakeSurveyApi(surveyBehavior = { FakeApiResponse.Success }))

        val result = h.engine.sync()

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), result.succeededIds)
        assertTrue(result.failedIds.isEmpty())
        assertNull(result.stoppedReason)
        assertEquals(5, h.repo.synced.size)
    }

    @Test
    fun `6th survey fails with 500, first 5 succeed`() = runTest {
        val surveys = (1..8).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            if (call < 5) FakeApiResponse.Success else FakeApiResponse.ServerError(500)
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), result.succeededIds)
        assertTrue("s6" in result.failedIds)
        assertEquals(5, h.repo.synced.size)
        assertTrue(h.repo.failed.any { it.first == "s6" })
        assertTrue(h.repo.retried.contains("s6"))
    }

    @Test
    fun `timeout stops sync early with NetworkLost`() = runTest {
        val surveys = (1..5).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            when (call) {
                0, 1 -> FakeApiResponse.Success
                else -> FakeApiResponse.Timeout
            }
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1", "s2"), result.succeededIds)
        assertEquals(listOf("s3"), result.failedIds)
        assertEquals(SyncStopReason.NetworkLost, result.stoppedReason)
        assertEquals(2, h.repo.synced.size)
    }

    @Test
    fun `IOException stops sync early with NetworkLost`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            if (call == 0) FakeApiResponse.Success else FakeApiResponse.NetworkLost
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1"), result.succeededIds)
        assertEquals(listOf("s2"), result.failedIds)
        assertEquals(SyncStopReason.NetworkLost, result.stoppedReason)
    }

    @Test
    fun `ServerError 500 marks failed and continues to next survey`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            if (call == 1) FakeApiResponse.ServerError(503) else FakeApiResponse.Success
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1", "s3"), result.succeededIds)
        assertEquals(listOf("s2"), result.failedIds)
        assertNull(result.stoppedReason)
        assertTrue(h.repo.retried.contains("s2"))
    }

    @Test
    fun `ServerError 400 marks failed, pins retry to max, and continues`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            if (call == 1) FakeApiResponse.ServerError(422) else FakeApiResponse.Success
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1", "s3"), result.succeededIds)
        assertEquals(listOf("s2"), result.failedIds)
        assertNull(result.stoppedReason)
        assertTrue(h.repo.pinnedRetry.contains("s2"))
        assertTrue(h.repo.retried.none { it == "s2" })
    }

    @Test
    fun `unknown fatal error stops entire sync with FatalError`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val api = FakeSurveyApi(surveyBehavior = { call ->
            if (call == 1) FakeApiResponse.UnknownError else FakeApiResponse.Success
        })
        val h = engine(surveys, api)

        val result = h.engine.sync()

        assertEquals(listOf("s1"), result.succeededIds)
        assertEquals(listOf("s2"), result.failedIds)
        assertEquals(SyncStopReason.FatalError, result.stoppedReason)
    }

    @Test
    fun `empty queue returns empty result with no stop reason`() = runTest {
        val h = engine(emptyList(), FakeSurveyApi(surveyBehavior = { FakeApiResponse.Success }))

        val result = h.engine.sync()

        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failedIds.isEmpty())
        assertNull(result.stoppedReason)
    }

    @Test
    fun `concurrent sync calls do not double-execute`() = runTest {
        val surveys = (1..4).map { survey("s$it") }
        var uploadCount = 0
        val api = FakeSurveyApi(surveyBehavior = { uploadCount++; FakeApiResponse.Success })
        val h = engine(surveys, api)

        val first = async { h.engine.sync() }
        val second = async { h.engine.sync() }

        val r1 = first.await()
        val r2 = second.await()

        assertEquals(4, uploadCount)
        val combined = r1.succeededIds + r2.succeededIds
        assertEquals(4, combined.size)
        assertTrue(r1.failedIds.isEmpty() && r2.failedIds.isEmpty())
    }

    @Test
    fun `sync stops immediately with LowStorage when available bytes below threshold`() = runTest {
        val surveys = (1..3).map { survey("s$it") }
        val h = engine(
            surveys,
            FakeSurveyApi(surveyBehavior = { FakeApiResponse.Success }),
            availableStorageBytes = 10 * 1024 * 1024L,
        )

        val result = h.engine.sync()

        assertEquals(SyncStopReason.LowStorage, result.stoppedReason)
        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failedIds.isEmpty())
        assertTrue(h.repo.synced.isEmpty())
    }

    @Test
    fun `attachments are uploaded after survey metadata and marked as uploaded`() = runTest {
        val att = attachment("att-1", "s1")
        val surveys = listOf(survey("s1", attachments = listOf(att)))
        val h = engine(surveys, FakeSurveyApi(
            surveyBehavior = { FakeApiResponse.Success },
            attachmentBehavior = { FakeApiResponse.Success },
        ))

        val result = h.engine.sync()

        assertEquals(listOf("s1"), result.succeededIds)
        assertNull(result.stoppedReason)
        assertTrue(h.attachmentRepo.uploaded.contains("att-1"))
        assertTrue(h.fs.deleted.contains(att.localPath))
    }

    @Test
    fun `retriable attachment error increments retry but continues to next survey`() = runTest {
        val att = attachment("att-1", "s1")
        val surveys = listOf(survey("s1", attachments = listOf(att)), survey("s2"))
        val h = engine(surveys, FakeSurveyApi(
            surveyBehavior = { FakeApiResponse.Success },
            attachmentBehavior = { FakeApiResponse.ServerError(503) },
        ))

        h.engine.sync()
        assertTrue(h.attachmentRepo.failed.any { it.first == "att-1" })
        // s1 failed due to attachment, but s2 (no attachments) may still proceed
    }

    @Test
    fun `network lost during attachment upload stops entire sync`() = runTest {
        val att = attachment("att-1", "s1")
        val surveys = listOf(survey("s1", attachments = listOf(att)), survey("s2"))
        val h = engine(surveys, FakeSurveyApi(
            surveyBehavior = { FakeApiResponse.Success },
            attachmentBehavior = { FakeApiResponse.NetworkLost },
        ))

        val result = h.engine.sync()

        assertEquals(SyncStopReason.NetworkLost, result.stoppedReason)
        assertTrue("s1" in result.failedIds)
        assertTrue(result.succeededIds.isEmpty())
    }

    @Test
    fun `fatal attachment error marks survey failed and stops sync`() = runTest {
        val att = attachment("att-1", "s1")
        val surveys = listOf(survey("s1", attachments = listOf(att)), survey("s2"))
        val h = engine(surveys, FakeSurveyApi(
            surveyBehavior = { FakeApiResponse.Success },
            attachmentBehavior = { FakeApiResponse.ServerError(400) },
        ))

        val result = h.engine.sync()

        assertEquals(SyncStopReason.FatalError, result.stoppedReason)
        assertTrue("s1" in result.failedIds)
        assertTrue(h.repo.failed.any { it.first == "s1" })
        assertTrue(result.succeededIds.isEmpty())
    }

    @Test
    fun `partial attachment success persists before stop`() = runTest {
        val att1 = attachment("att-1", "s1")
        val att2 = attachment("att-2", "s1")
        val surveys = listOf(survey("s1", attachments = listOf(att1, att2)))
        var attachCall = 0
        val h = engine(surveys, FakeSurveyApi(
            surveyBehavior = { FakeApiResponse.Success },
            attachmentBehavior = { if (attachCall++ == 0) FakeApiResponse.Success else FakeApiResponse.ServerError(400) },
        ))

        h.engine.sync()

        // att-1 was uploaded and persisted before att-2 caused a fatal stop
        assertTrue(h.attachmentRepo.uploaded.contains("att-1"))
        assertTrue(h.fs.deleted.contains(att1.localPath))
        assertTrue(h.attachmentRepo.failed.any { it.first == "att-2" })
    }
}