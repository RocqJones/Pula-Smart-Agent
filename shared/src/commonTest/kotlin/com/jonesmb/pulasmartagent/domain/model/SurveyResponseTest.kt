package com.jonesmb.pulasmartagent.domain.model

import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SurveyResponseTest {

    private fun buildResponse(
        status: SyncStatus = SyncStatus.PENDING,
        nodes: List<ResponseNode> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ) = SurveyResponse(
        id = "survey-001",
        farmerId = "farmer-42",
        createdAt = Instant.parse("2026-03-04T08:00:00Z"),
        status = status,
        retryCount = 0,
        nodes = nodes,
        attachments = attachments,
    )

    @Test
    fun `SurveyResponse holds correct farmer and id`() {
        val response = buildResponse()
        assertEquals("survey-001", response.id)
        assertEquals("farmer-42", response.farmerId)
    }

    @Test
    fun `default status is PENDING`() {
        assertEquals(SyncStatus.PENDING, buildResponse().status)
    }

    @Test
    fun `Answer node stores questionId and answer`() {
        val node = ResponseNode.Answer(questionId = "q1", answer = "Yes")
        assertEquals("q1", node.questionId)
        assertEquals("Yes", node.answer)
    }

    @Test
    fun `RepeatingSection holds dynamic number of instances`() {
        val instances = (1..3).map { i ->
            SectionInstance(
                answers = listOf(QuestionAnswer(questionId = "plot_size", answer = "$i ha")),
            )
        }
        val node = ResponseNode.RepeatingSection(sectionId = "plots", instances = instances)
        assertEquals(3, node.instances.size)
        assertEquals("2 ha", node.instances[1].answers.first().answer)
    }

    @Test
    fun `ResponseNode sealed class is exhaustively handled`() {
        val nodes: List<ResponseNode> = listOf(
            ResponseNode.Answer("q1", "42"),
            ResponseNode.RepeatingSection("livestock", emptyList()),
        )
        nodes.forEach { node ->
            when (node) {
                is ResponseNode.Answer          -> assertIs<ResponseNode.Answer>(node)
                is ResponseNode.RepeatingSection -> assertIs<ResponseNode.RepeatingSection>(node)
            }
        }
    }

    @Test
    fun `Attachment starts with PENDING upload status`() {
        val attachment = Attachment(
            id = "att-001",
            surveyId = "survey-001",
            localPath = "/data/user/0/images/photo.jpg",
            sizeBytes = 204_800L,
            createdAt = Instant.parse("2026-03-04T08:00:00Z"),
            uploadStatus = AttachmentUploadStatus.PENDING,
            retryCount = 0,
            lastError = null,
        )
        assertEquals(AttachmentUploadStatus.PENDING, attachment.uploadStatus)
        assertEquals("survey-001", attachment.surveyId)
    }

    @Test
    fun `SurveyResponse with mixed nodes and attachments`() {
        val response = buildResponse(
            nodes = listOf(
                ResponseNode.Answer("crop_type", "Maize"),
                ResponseNode.RepeatingSection(
                    sectionId = "fields",
                    instances = listOf(
                        SectionInstance(listOf(QuestionAnswer("field_size", "2.5"))),
                        SectionInstance(listOf(QuestionAnswer("field_size", "1.0"))),
                    ),
                ),
            ),
            attachments = listOf(
                Attachment(
                    "a1", "survey-001", "/img/field1.jpg",
                    102_400L, Instant.parse("2026-03-04T08:00:00Z"
                    ), AttachmentUploadStatus.PENDING, 0, null
                )
            )
        )

        assertEquals(2, response.nodes.size)
        assertEquals(1, response.attachments.size)

        val repeating = response.nodes[1]
        assertIs<ResponseNode.RepeatingSection>(repeating)
        assertEquals(2, repeating.instances.size)
        assertTrue(response.attachments.all { it.uploadStatus == AttachmentUploadStatus.PENDING })
    }
}

