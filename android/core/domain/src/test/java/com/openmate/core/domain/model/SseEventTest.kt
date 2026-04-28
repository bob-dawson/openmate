package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SseEventTest {
    @Test
    fun create() {
        val event = SseEvent(
            type = "message.part.delta",
            payload = """{"text":"hi"}""",
        )
        assertThat(event.type).isEqualTo("message.part.delta")
        assertThat(event.payload).isEqualTo("""{"text":"hi"}""")
    }
}
