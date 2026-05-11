package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class EventReplayerTest {

    @Test
    fun replay_stepFailed_marksRunningToolAsError() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "assistant-1",
                    type = "session.next.step.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("agent", JsonPrimitive("build"))
                        put("model", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-input-started",
                    type = "session.next.tool.input.started.1",
                    timestamp = 1_050L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-1"))
                        put("name", JsonPrimitive("bash"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-1"))
                        put("input", buildJsonObject { put("cmd", "pwd") })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-step-failed",
                    type = "session.next.step.failed.1",
                    timestamp = 1_200L,
                    extra = buildJsonObject {
                        put("error", buildJsonObject {
                            put("message", JsonPrimitive("MessageAbortedError"))
                        })
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val assistantUpdate = changes.last() as ReplayChange.Update
        val content = assistantUpdate.data["content"]!!.jsonArray
        val tool = content.single().jsonObject

        assertThat(tool["state"]!!.jsonObject["status"]!!.jsonPrimitive.content).isEqualTo("error")
        assertThat(tool["state"]!!.jsonObject["error"]!!.jsonPrimitive.content).isEqualTo("Tool execution aborted")
        assertThat(assistantUpdate.completedAt).isEqualTo(1_200L)
    }

    private fun replayEvent(
        id: String,
        type: String,
        timestamp: Long,
        extra: JsonObject = buildJsonObject {},
    ): ReplayEvent {
        val data = extra.toMutableMap()
        data["timestamp"] = JsonPrimitive(timestamp)
        if (!data.containsKey("callID") && type.contains("tool.called")) {
            data["callID"] = JsonPrimitive("call-1")
        }
        if (!data.containsKey("name") && type.contains("tool.called")) {
            data["name"] = JsonPrimitive("bash")
        }
        return ReplayEvent(id = id, type = type, data = JsonObject(data))
    }
}
