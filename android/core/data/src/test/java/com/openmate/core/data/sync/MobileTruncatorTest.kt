package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class MobileTruncatorTest {

    @Test
    fun stripsInlineToolMediaButKeepsMetadata() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "read",
                  "state": {
                    "status": "completed",
                    "content": [
                      {"type": "text", "text": "Image read successfully"},
                      {"type": "file", "uri": "data:image/png;base64,AAAA", "mime": "image/png", "name": "shot.png"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val truncated = MobileTruncator.truncate("assistant", data)
        val items = truncated["content"]!!.jsonArray[0].jsonObject["state"]!!.jsonObject["content"]!!.jsonArray

        assertThat(items[1].jsonObject["uri"]).isNull()
        assertThat(items[1].jsonObject["mime"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(items[1].jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("shot.png")
    }

    @Test
    fun keepsMediaRefs() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "read",
                  "state": {
                    "status": "completed",
                    "content": [
                      {"type": "file", "uri": "media:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "mime": "image/png"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val truncated = MobileTruncator.truncate("assistant", data)
        val item = truncated["content"]!!.jsonArray[0].jsonObject["state"]!!.jsonObject["content"]!!.jsonArray[0]

        assertThat(item.jsonObject["uri"]!!.jsonPrimitive.content).startsWith("media:")
    }
}
