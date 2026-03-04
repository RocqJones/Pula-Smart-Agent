package com.jonesmb.pulasmartagent.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jonesmb.pulasmartagent.db.SmartAgentDatabase
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.ResponseNode
import com.jonesmb.pulasmartagent.domain.model.SectionInstance
import com.jonesmb.pulasmartagent.domain.model.QuestionAnswer
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurveyRepositoryImplTest {

    private lateinit var repo: SurveyRepositoryImpl

    @Before
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SmartAgentDatabase.Schema.create(driver)
        repo = SurveyRepositoryImpl(driver)
    }

    // helpers
    private fun survey(
        id: String = "survey-1",
        nodes: List<ResponseNode> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ) = SurveyResponse(
        id = id,
        farmerId = "farmer-42",
        createdAt = Instant.parse("2026-03-04T08:00:00Z"),
        status = SyncStatus.PENDING,
        retryCount = 0,
        nodes = nodes,
        attachments = attachments,
    )

    // save and retrieve
    @Test
    fun `saved survey appears in pending list`() = runTest {
        repo.saveSurvey(survey("s1"))

        val pending = repo.getPendingSurveys()

        assertEquals(1, pending.size)
        assertEquals("s1", pending.first().id)
        assertEquals("farmer-42", pending.first().farmerId)
    }

    @Test
    fun `saved survey preserves createdAt and retryCount`() = runTest {
        val createdAt = Instant.parse("2026-03-04T08:00:00Z")
        repo.saveSurvey(survey("s1"))

        val retrieved = repo.getPendingSurveys().first()

        assertEquals(createdAt, retrieved.createdAt)
        assertEquals(0, retrieved.retryCount)
    }

    @Test
    fun `saved survey with Answer nodes reconstructs nodes correctly`() = runTest {
        val nodes = listOf(
            ResponseNode.Answer(questionId = "crop", answer = "Maize"),
            ResponseNode.Answer(questionId = "area", answer = "2.5"),
        )
        repo.saveSurvey(survey("s1", nodes = nodes))

        val retrieved = repo.getPendingSurveys().first()

        assertEquals(2, retrieved.nodes.size)
        val answer = retrieved.nodes.filterIsInstance<ResponseNode.Answer>()
        assertEquals("Maize", answer.first { it.questionId == "crop" }.answer)
        assertEquals("2.5", answer.first { it.questionId == "area" }.answer)
    }

    @Test
    fun `saved survey with repeating section reconstructs instances`() = runTest {
        val instances = listOf(
            SectionInstance(listOf(QuestionAnswer("field_size", "1.0"))),
            SectionInstance(listOf(QuestionAnswer("field_size", "3.5"))),
        )
        val nodes = listOf(ResponseNode.RepeatingSection(sectionId = "fields", instances = instances))
        repo.saveSurvey(survey("s1", nodes = nodes))

        val retrieved = repo.getPendingSurveys().first()
        val section = retrieved.nodes.filterIsInstance<ResponseNode.RepeatingSection>().first()

        assertEquals(2, section.instances.size)
        assertEquals("1.0", section.instances[0].answers.first().answer)
        assertEquals("3.5", section.instances[1].answers.first().answer)
    }

    @Test
    fun `saved survey with attachments reconstructs attachments`() = runTest {
        val attachments = listOf(
            Attachment("att-1", "s1", "/img/photo1.jpg", AttachmentUploadStatus.PENDING),
            Attachment("att-2", "s1", "/img/photo2.jpg", AttachmentUploadStatus.PENDING),
        )
        repo.saveSurvey(survey("s1", attachments = attachments))

        val retrieved = repo.getPendingSurveys().first()

        assertEquals(2, retrieved.attachments.size)
        assertNotNull(retrieved.attachments.firstOrNull { it.id == "att-1" })
        assertNotNull(retrieved.attachments.firstOrNull { it.id == "att-2" })
    }

    @Test
    fun `only PENDING surveys are returned by getPendingSurveys`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.saveSurvey(survey("s2"))
        repo.markAsSynced("s2")

        val pending = repo.getPendingSurveys()

        assertEquals(1, pending.size)
        assertEquals("s1", pending.first().id)
    }

    // status updates
    @Test
    fun `markAsSynced removes survey from pending list`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.markAsSynced("s1")

        assertTrue(repo.getPendingSurveys().isEmpty())
    }

    @Test
    fun `markAsFailed removes survey from pending list`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.markAsFailed("s1", SyncError.ServerError(503))

        assertTrue(repo.getPendingSurveys().isEmpty())
    }

    @Test
    fun `markAsFailed with NoInternet removes survey from pending`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.markAsFailed("s1", SyncError.NoInternet)

        assertTrue(repo.getPendingSurveys().isEmpty())
    }

    @Test
    fun `multiple surveys transition status independently`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.saveSurvey(survey("s2"))
        repo.saveSurvey(survey("s3"))

        repo.markAsSynced("s1")
        repo.markAsFailed("s3", SyncError.Timeout)

        val pending = repo.getPendingSurveys()
        assertEquals(1, pending.size)
        assertEquals("s2", pending.first().id)
    }

    // retry count
    @Test
    fun `incrementRetry increases retryCount by 1`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.incrementRetry("s1")

        // Reset to PENDING so getPendingSurveys returns it
        // (incrementRetry only bumps the counter, not the status)
        val pending = repo.getPendingSurveys()
        assertEquals(1, pending.first().retryCount)
    }

    @Test
    fun `incrementRetry called three times yields retryCount of 3`() = runTest {
        repo.saveSurvey(survey("s1"))
        repeat(3) { repo.incrementRetry("s1") }

        assertEquals(3, repo.getPendingSurveys().first().retryCount)
    }

    @Test
    fun `incrementRetry does not affect other surveys`() = runTest {
        repo.saveSurvey(survey("s1"))
        repo.saveSurvey(survey("s2"))
        repo.incrementRetry("s1")

        val pending = repo.getPendingSurveys()
        assertEquals(1, pending.first { it.id == "s1" }.retryCount)
        assertEquals(0, pending.first { it.id == "s2" }.retryCount)
    }
}

