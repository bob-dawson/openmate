package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MessageImagesTest {

    private fun data(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun imageFiles_keepsOnlyImagesAndPreservesOrder() {
        val payload = data(
            """
            {"text":"hi","files":[
              {"mime":"image/jpeg","name":"a.jpg"},
              {"mime":"text/plain","name":"notes.txt"},
              {"mime":"image/png","name":"b.png"}
            ]}
            """.trimIndent(),
        )

        val images = MessageImages.imageFiles(payload)

        assertThat(images).hasSize(2)
        assertThat(images[0]["name"]?.toString()).contains("a.jpg")
        assertThat(images[1]["name"]?.toString()).contains("b.png")
    }

    @Test
    fun base64At_usesImageOrderingSkippingOtherFiles() {
        val payload = data(
            """
            {"files":[
              {"mime":"text/plain","name":"a.txt"},
              {"mime":"image/jpeg","name":"first.jpg","data":"AAAA"},
              {"mime":"image/jpeg","name":"second.jpg","data":"BBBB"}
            ]}
            """.trimIndent(),
        )

        assertThat(MessageImages.base64At(payload, 0)).isEqualTo("AAAA")
        assertThat(MessageImages.base64At(payload, 1)).isEqualTo("BBBB")
        assertThat(MessageImages.base64At(payload, 2)).isNull()
    }

    @Test
    fun base64At_returnsNullWhenDataWasStripped() {
        val payload = data("""{"files":[{"mime":"image/png","name":"a.png"}]}""")

        assertThat(MessageImages.base64At(payload, 0)).isNull()
    }

    @Test
    fun imageFiles_handlesMissingOrMalformedFiles() {
        assertThat(MessageImages.imageFiles(data("""{"text":"hi"}"""))).isEmpty()
        assertThat(MessageImages.imageFiles(data("""{"files":"nope"}"""))).isEmpty()
        assertThat(MessageImages.imageFiles(data("""{"files":[1,"x",{"mime":"image/png"}]}"""))).hasSize(1)
    }
}
