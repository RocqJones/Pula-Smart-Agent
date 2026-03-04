package com.jonesmb.pulasmartagent.domain.model

data class QuestionAnswer(
    val questionId: String,
    val answer: String,
)

/**
 * One filled-in instance of a repeating sections
 */
data class SectionInstance(
    val answers: List<QuestionAnswer>,
)

/**
 * Each can either have a direct answer or a repeating group.
 * We use sealed class so the compiler enforces exhaustive handling at every call site.
 */
sealed class ResponseNode {

    // A single question-answer pair at the current nesting level.
    data class Answer(
        val questionId: String,
        val answer: String,
    ) : ResponseNode()

    data class RepeatingSection(
        val sectionId: String,
        val instances: List<SectionInstance>,
    ) : ResponseNode()
}

