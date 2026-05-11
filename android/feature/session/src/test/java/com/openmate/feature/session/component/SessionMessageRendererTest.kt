package com.openmate.feature.session.component

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
}
