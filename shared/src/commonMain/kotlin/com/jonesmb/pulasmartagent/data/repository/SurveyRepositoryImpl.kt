package com.jonesmb.pulasmartagent.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.jonesmb.pulasmartagent.db.Response_node
import com.jonesmb.pulasmartagent.db.SmartAgentDatabase
import com.jonesmb.pulasmartagent.domain.errors.SyncError
import com.jonesmb.pulasmartagent.domain.model.Attachment
import com.jonesmb.pulasmartagent.domain.model.QuestionAnswer
import com.jonesmb.pulasmartagent.domain.model.ResponseNode
import com.jonesmb.pulasmartagent.domain.model.SectionInstance
import com.jonesmb.pulasmartagent.domain.model.SurveyResponse
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import com.jonesmb.pulasmartagent.domain.repository.SurveyRepository
import com.jonesmb.pulasmartagent.core.constants.StoragePolicy
import com.jonesmb.pulasmartagent.core.constants.Constants.NODE_TYPE_ANSWER
import com.jonesmb.pulasmartagent.core.constants.Constants.NODE_TYPE_REPEATING_SECTION
import com.jonesmb.pulasmartagent.core.extensions.toDbString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant


class SurveyRepositoryImpl(driver: SqlDriver) : SurveyRepository {

    private val db = SmartAgentDatabase(driver)
    private val surveyQueries = db.surveyResponseQueries
    private val nodeQueries = db.responseNodeQueries
    private val attachmentQueries = db.attachmentQueries

    override suspend fun saveSurvey(response: SurveyResponse) = withContext(Dispatchers.Default) {
        db.transaction {
            surveyQueries.insert(
                id = response.id,
                farmer_id = response.farmerId,
                created_at = response.createdAt.toEpochMilliseconds(),
                status = response.status.name,
                retry_count = response.retryCount.toLong(),
                last_error = null,
            )
            response.nodes.forEach { node -> insertNode(node, response.id, parentNodeId = null) }
            response.attachments.forEach { attachment ->
                attachmentQueries.insert(
                    id = attachment.id,
                    survey_id = attachment.surveyId,
                    local_path = attachment.localPath,
                    size_bytes = attachment.sizeBytes,
                    created_at = attachment.createdAt.toEpochMilliseconds(),
                    upload_status = attachment.uploadStatus.name,
                    retry_count = attachment.retryCount.toLong(),
                    last_error = attachment.lastError,
                )
            }
        }
    }

    override suspend fun getPendingSurveys(): List<SurveyResponse> = withContext(Dispatchers.Default) {
        surveyQueries.selectPending(maxRetry = StoragePolicy.MAX_SURVEY_RETRY.toLong()).executeAsList().map { row ->
            val nodes = buildNodeTree(row.id)
            val attachments = attachmentQueries.selectBySurveyId(row.id).executeAsList().map {
                it.toDomain()
            }
            row.toDomain(nodes, attachments)
        }
    }

    // Status mutations — each runs atomically inside a transaction
    override suspend fun markAsSynced(id: String) = withContext(Dispatchers.Default) {
        db.transaction {
            surveyQueries.updateStatus(status = SyncStatus.SYNCED.name, id = id)
        }
    }

    override suspend fun markAsFailed(id: String, error: SyncError) = withContext(Dispatchers.Default) {
        db.transaction {
            surveyQueries.updateStatusWithError(
                status = SyncStatus.FAILED.name,
                last_error = error.toDbString(),
                id = id,
            )
        }
    }

    override suspend fun incrementRetry(id: String) = withContext(Dispatchers.Default) {
        db.transaction {
            surveyQueries.incrementRetry(id = id)
        }
    }

    override suspend fun pinRetryToMax(id: String) = withContext(Dispatchers.Default) {
        db.transaction {
            surveyQueries.pinRetryToMax(retry_count = StoragePolicy.MAX_SURVEY_RETRY.toLong(), id = id)
        }
    }

    /**
     * Tree reconstruction
     *
     * Fetches all [Response_node] rows for a survey, groups them by [Response_node.parent_node_id],
     * then moves from root nodes downward to reconstruct the main [ResponseNode] tree.
     *
     * Supports arbitrary nesting depth: a RepeatingSection child can itself contain further
     * RepeatingSections.
     */
    private fun buildNodeTree(surveyId: String): List<ResponseNode> {
        val rows = nodeQueries.selectBySurveyId(surveyId).executeAsList()
        val byParent: Map<String?, List<Response_node>> = rows.groupBy { it.parent_node_id }
        return buildChildren(parentId = null, byParent = byParent)
    }

    private fun buildChildren(
        parentId: String?, byParent: Map<String?, List<Response_node>>,
    ): List<ResponseNode> = byParent[parentId].orEmpty().map { row -> row.toResponseNode(byParent) }

    private fun Response_node.toResponseNode(
        byParent: Map<String?, List<Response_node>>,
    ): ResponseNode = when (type) {
        NODE_TYPE_ANSWER -> ResponseNode.Answer(
            questionId = question_id.orEmpty(),
            answer = answer.orEmpty(),
        )
        NODE_TYPE_REPEATING_SECTION -> {
            val instanceRoots = byParent[id].orEmpty()
            val instances = instanceRoots.map { instanceRoot ->
                val answerRows = byParent[instanceRoot.id].orEmpty()
                SectionInstance(
                    answers = answerRows.map { a ->
                        QuestionAnswer(
                            questionId = a.question_id.orEmpty(),
                            answer = a.answer.orEmpty()
                        )
                    },
                )
            }
            ResponseNode.RepeatingSection(
                sectionId = section_id.orEmpty(),
                instances = instances
            )
        }
        else -> error("Unknown response_node type: $type")
    }

    // DB row - domain mappers
    private fun com.jonesmb.pulasmartagent.db.Survey_response.toDomain(
        nodes: List<ResponseNode>,
        attachments: List<Attachment>,
    ) = SurveyResponse(
        id = id,
        farmerId = farmer_id,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        status = SyncStatus.valueOf(status),
        retryCount = retry_count.toInt(),
        nodes = nodes,
        attachments = attachments,
    )

    private fun com.jonesmb.pulasmartagent.db.Attachment.toDomain() = Attachment(
        id = id,
        surveyId = survey_id,
        localPath = local_path,
        sizeBytes = size_bytes,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        uploadStatus = AttachmentUploadStatus.valueOf(upload_status),
        retryCount = retry_count.toInt(),
        lastError = last_error,
    )

    /**
     * Domain - DB helpers
     *
     * Recursively flattens a [ResponseNode] tree into individual row inserts.
     *
     * For a [ResponseNode.RepeatingSection] each [SectionInstance] gets its own
     * synthetic row (type = REPEATING_SECTION, parent = sectionNodeId) so the
     * hierarchy can be reconstructed from the flat table.
     */
    private fun insertNode(node: ResponseNode, surveyId: String, parentNodeId: String?) {
        when (node) {
            is ResponseNode.Answer -> {
                val nodeId = "${surveyId}_${node.questionId}"
                nodeQueries.insert(
                    id = nodeId,
                    survey_id = surveyId,
                    parent_node_id = parentNodeId,
                    type = NODE_TYPE_ANSWER,
                    question_id = node.questionId,
                    answer = node.answer,
                    section_id = null,
                )
            }
            is ResponseNode.RepeatingSection -> {
                val sectionNodeId = "${surveyId}_${node.sectionId}"
                nodeQueries.insert(
                    id = sectionNodeId,
                    survey_id = surveyId,
                    parent_node_id = parentNodeId,
                    type = NODE_TYPE_REPEATING_SECTION,
                    question_id = null,
                    answer = null,
                    section_id = node.sectionId,
                )
                node.instances.forEachIndexed { index, instance ->
                    val instanceNodeId = "${sectionNodeId}_$index"
                    nodeQueries.insert(
                        id = instanceNodeId,
                        survey_id = surveyId,
                        parent_node_id = sectionNodeId,
                        type = NODE_TYPE_REPEATING_SECTION,
                        question_id = null,
                        answer = null,
                        section_id = node.sectionId,
                    )
                    instance.answers.forEach { qa ->
                        nodeQueries.insert(
                            id = "${instanceNodeId}_${qa.questionId}",
                            survey_id = surveyId,
                            parent_node_id = instanceNodeId,
                            type = NODE_TYPE_ANSWER,
                            question_id = qa.questionId,
                            answer = qa.answer,
                            section_id = null,
                        )
                    }
                }
            }
        }
    }
}


