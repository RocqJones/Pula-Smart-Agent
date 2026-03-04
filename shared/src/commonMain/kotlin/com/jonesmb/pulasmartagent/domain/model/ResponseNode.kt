package com.jonesmb.pulasmartagent.domain.model

/**
 * A single captured answer to one survey question.
 */
data class QuestionAnswer(
    val questionId: String,
    val answer: String,
)

/**
 * One filled-in instance of a repeating section (e.g. one livestock entry,
 * one field plot, one household member).
 */
data class SectionInstance(
    val answers: List<QuestionAnswer>,
)

/**
 * Each can either a direct answer or a repeating group.
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

