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
                QuestionItem("Choose option", listOf("A", "B", "C")),
            ),
        )
        assertThat(req.id).isEqualTo("q-1")
        assertThat(req.questions).hasSize(1)
        assertThat(req.questions[0].label).isEqualTo("Choose option")
        assertThat(req.questions[0].options).hasSize(3)
    }

    @Test
    fun questionItem_equality() {
        val item1 = QuestionItem("Q?", listOf("Y", "N"))
        val item2 = QuestionItem("Q?", listOf("Y", "N"))
        assertThat(item1).isEqualTo(item2)
    }
}
