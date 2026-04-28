package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageTest {
    @Test
    fun create_message_withTextParts() {
        val message = Message(
            id = "m-1",
            sessionID = "s-1",
            role = MessageRole.USER,
            createdAt = 1700000000000L,
            parts = listOf(Part.TextPart("Hello")),
        )
        assertThat(message.role).isEqualTo(MessageRole.USER)
        assertThat(message.parts).hasSize(1)
        assertThat((message.parts[0] as Part.TextPart).text).isEqualTo("Hello")
    }

    @Test
    fun messageRole_values() {
        assertThat(MessageRole.values()).asList()
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
    }
}
