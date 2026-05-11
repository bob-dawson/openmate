package com.openmate.feature.session.component

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SessionMessageRendererTest {

    @Test
    fun extractAssistantErrorMessage_returnsNestedErrorMessage() {
        val data = Json.parseToJsonElement(
            """
            {
              "finish": "error",
              "error": {
                "message": "Provider overloaded, retry later"
              },
              "content": [
                {
                  "type": "text",
                  "text": ""
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        assertThat(extractAssistantErrorMessage(data)).isEqualTo("Provider overloaded, retry later")
    }

    @Test
    fun extractSubtaskSessionId_acceptsUppercaseSessionIdInMetadata() {
        val metadata = buildJsonObject {
            put("sessionID", JsonPrimitive("ses_subtask_upper"))
        }

        assertThat(extractSubtaskSessionId(metadata = metadata, structured = null, resultText = null))
            .isEqualTo("ses_subtask_upper")
    }

    @Test
    fun extractSubtaskSessionId_fallsBackToStructuredSessionIdWhileRunning() {
        val structured = buildJsonObject {
            put("sessionId", JsonPrimitive("ses_subtask_structured"))
        }

        assertThat(extractSubtaskSessionId(metadata = null, structured = structured, resultText = null))
            .isEqualTo("ses_subtask_structured")
    }
}
