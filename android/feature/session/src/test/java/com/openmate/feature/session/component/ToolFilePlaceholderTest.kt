package com.openmate.feature.session.component

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ToolFilePlaceholderTest {

    @Test
    fun fileItemsBecomePlaceholdersInsteadOfBase64() {
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
                      {"type": "file", "uri": "data:image/png;base64,AAAA", "mime": "image/png", "name": "a.png"},
                      {"type": "file", "uri": "media:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "mime": "application/pdf", "name": "b.pdf"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val item = extractToolItems(data, imageLabel = "[图片]", fileLabel = "[文件]").single()

        assertThat(item.result).isEqualTo(
            "Image read successfully\n[图片] a.png\n[文件] b.pdf",
        )
        assertThat(item.result).doesNotContain("base64")
        assertThat(item.result).doesNotContain("media:")
    }

    @Test
    fun fileOnlyResultStillCountsAsOutput() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "generate_image",
                  "state": {
                    "status": "completed",
                    "content": [
                      {"type": "file", "uri": "data:image/png;base64,AAAA", "mime": "image/png", "name": "out.png"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val item = extractToolItems(data, imageLabel = "[图片]", fileLabel = "[文件]").single()

        assertThat(item.result).isEqualTo("[图片] out.png")
    }
}
