package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuestionRequestTest {
    @Test
    fun create() {
        val req = QuestionRequest(
            id = "q-1",
            sessionID = "s-1",
            questions = listOf(
                QuestionInfo(
                    question = "Choose option",
                    header = "Pick one",
                    options = listOf(
                        QuestionOption(label = "A", description = "Option A"),
                        QuestionOption(label = "B", description = "Option B"),
                        QuestionOption(label = "C", description = "Option C"),
                    ),
                ),
            ),
        )
        assertThat(req.id).isEqualTo("q-1")
        assertThat(req.questions).hasSize(1)
        assertThat(req.questions[0].question).isEqualTo("Choose option")
        assertThat(req.questions[0].options).hasSize(3)
    }

    @Test
    fun questionInfo_equality() {
        val item1 = QuestionInfo(
            question = "Q?",
            header = "Header",
            options = listOf(
                QuestionOption(label = "Y", description = "Yes"),
                QuestionOption(label = "N", description = "No"),
            ),
        )
        val item2 = QuestionInfo(
            question = "Q?",
            header = "Header",
            options = listOf(
                QuestionOption(label = "Y", description = "Yes"),
                QuestionOption(label = "N", description = "No"),
            ),
        )
        assertThat(item1).isEqualTo(item2)
    }
}
