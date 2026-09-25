package com.openmate.core.data.sse

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.network.SseData
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuestionEventHandlerTest {

    @Test
    fun formCreated_emitsQuestionRequestBoundToToolCall() = runTest {
        val handler = QuestionEventHandler()
        val collected = mutableListOf<QuestionRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.questions.collect { collected += it }
        }

        handler.handle("form.created", SseData(type = "form.created", properties = buildJsonObject {
            put("form", buildJsonObject {
                put("id", "frm_1")
                put("sessionID", "ses_1")
                put("title", "Questions")
                put("metadata", buildJsonObject {
                    put("kind", "question")
                    put("tool", buildJsonObject {
                        put("messageID", "msg_1")
                        put("id", "call_1")
                    })
                })
                put("fields", buildJsonArray {
                    add(buildJsonObject {
                        put("key", "q0")
                        put("type", "string")
                        put("title", "Pick one")
                        put("description", "Which one?")
                        put("custom", true)
                        put("options", buildJsonArray {
                            add(buildJsonObject { put("value", "a"); put("label", "A") })
                        })
                    })
                })
            })
        }))

        assertThat(collected).hasSize(1)
        val request = collected.single()
        assertThat(request.id).isEqualTo("frm_1")
        assertThat(request.sessionID).isEqualTo("ses_1")
        assertThat(request.tool?.callID).isEqualTo("call_1")
        assertThat(request.tool?.messageID).isEqualTo("msg_1")
        assertThat(request.questions).hasSize(1)
        assertThat(request.questions[0].multiple).isFalse()
        assertThat(request.questions[0].options.single().label).isEqualTo("A")
        job.cancel()
    }

    @Test
    fun formCancelled_emitsDismissedFormId() = runTest {
        val handler = QuestionEventHandler()
        val dismissed = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.dismissed.collect { dismissed += it }
        }

        handler.handle("form.cancelled", SseData(type = "form.cancelled", properties = buildJsonObject {
            put("id", "frm_1")
            put("sessionID", "ses_1")
        }))

        assertThat(dismissed).containsExactly("frm_1")
        job.cancel()
    }
}
