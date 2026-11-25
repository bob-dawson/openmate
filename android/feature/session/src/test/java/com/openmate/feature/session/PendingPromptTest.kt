package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import com.openmate.core.network.dto.SessionInboxItemDto
import com.openmate.core.network.dto.SessionInboxTimeDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PendingPromptTest {

    @Test
    fun mapsUserInboxItem() {
        val payload = Json.parseToJsonElement(
            """
            {
              "text": "hello",
              "files": [
                {"mime": "image/png", "name": "a.png"},
                {"mime": "application/pdf", "name": "b.pdf"}
              ]
            }
            """.trimIndent(),
        ).jsonObject
        val dto = SessionInboxItemDto(
            id = "msg_1",
            type = "user",
            delivery = "queue",
            time = SessionInboxTimeDto(created = 42),
            payload = payload,
        )

        val prompt = dto.toPendingPrompt()

        assertThat(prompt).isNotNull()
        requireNotNull(prompt)
        assertThat(prompt.id).isEqualTo("msg_1")
        assertThat(prompt.text).isEqualTo("hello")
        assertThat(prompt.delivery).isEqualTo(PromptDelivery.QUEUE)
        assertThat(prompt.createdAt).isEqualTo(42)
        assertThat(prompt.imageCount).isEqualTo(1)
        assertThat(prompt.fileCount).isEqualTo(1)
        assertThat(prompt.hasAttachments).isTrue()
    }

    @Test
    fun ignoresNonUserAndEmptyItems() {
        assertThat(
            SessionInboxItemDto(id = "m", type = "compaction", payload = Json.parseToJsonElement("{}").jsonObject)
                .toPendingPrompt(),
        ).isNull()
        assertThat(
            SessionInboxItemDto(id = "m", type = "user", payload = Json.parseToJsonElement("{}").jsonObject)
                .toPendingPrompt(),
        ).isNull()
    }

    @Test
    fun deliveryFallsBackToSteer() {
        assertThat(PromptDelivery.fromWire(null)).isEqualTo(PromptDelivery.STEER)
        assertThat(PromptDelivery.fromWire("unknown")).isEqualTo(PromptDelivery.STEER)
        assertThat(PromptDelivery.fromWire("queue")).isEqualTo(PromptDelivery.QUEUE)
    }
}
