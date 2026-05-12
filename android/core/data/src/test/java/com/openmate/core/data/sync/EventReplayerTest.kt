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
import kotlinx.serialization.json.longOrNull
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

    @Test
    fun replay_compactionEnded_marksCompactionCompleted() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "compaction-1",
                    type = "session.next.compaction.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("reason", JsonPrimitive("auto"))
                    },
                ),
                replayEvent(
                    id = "compaction-1",
                    type = "session.next.compaction.ended.1",
                    timestamp = 2_000L,
                    extra = buildJsonObject {
                        put("text", JsonPrimitive("condensed prior context"))
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val update = changes.last() as ReplayChange.Update

        assertThat(update.completedAt).isEqualTo(2_000L)
        assertThat(update.data["summary"]!!.jsonPrimitive.content).isEqualTo("condensed prior context")
        assertThat(update.data["time"]!!.jsonObject["completed"]!!.jsonPrimitive.longOrNull).isEqualTo(2_000L)
    }

    @Test
    fun replay_compactionEnded_updatesExistingIncompleteCompactionFromDb() = runTest {
        val replayer = EventReplayer()
        val existing = SessionMessageEntity(
            id = "compaction-1",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "manual",
                  "summary": "",
                  "time": {
                    "created": 1000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 1_000L,
            completedAt = null,
        )

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "evt-ended",
                    type = "session.next.compaction.ended.1",
                    timestamp = 2_000L,
                    extra = buildJsonObject {
                        put("text", JsonPrimitive("condensed prior context"))
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { action ->
                when (action) {
                    is EventReplayer.DbLoader.Action.LoadById -> null
                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant -> null
                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction -> existing
                }
            },
        )

        val update = changes.single() as ReplayChange.Update

        assertThat(update.id).isEqualTo("compaction-1")
        assertThat(update.completedAt).isEqualTo(2_000L)
        assertThat(update.data["summary"]!!.jsonPrimitive.content).isEqualTo("condensed prior context")
        assertThat(update.data["time"]!!.jsonObject["completed"]!!.jsonPrimitive.longOrNull).isEqualTo(2_000L)
    }

    @Test
    fun replay_runningTaskTool_keepsSessionIdForNavigation() = runTest {
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
                        put("callID", JsonPrimitive("call-task-1"))
                        put("name", JsonPrimitive("task"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-task-1"))
                        put("input", buildJsonObject { put("description", JsonPrimitive("Subtask")) })
                        put("provider", buildJsonObject {})
                        put("metadata", buildJsonObject { put("sessionId", JsonPrimitive("ses_subtask_1")) })
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val assistantUpdate = changes.last() as ReplayChange.Update
        val tool = assistantUpdate.data["content"]!!.jsonArray.single().jsonObject
        val metadata = tool["state"]!!.jsonObject["metadata"]!!.jsonObject

        assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("task")
        assertThat(tool["state"]!!.jsonObject["status"]!!.jsonPrimitive.content).isEqualTo("running")
        assertThat(metadata["sessionId"]!!.jsonPrimitive.content).isEqualTo("ses_subtask_1")
    }

    @Test
    fun replay_messagePartUpdated_appendsPatchPartToAssistantContent() = runTest {
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
                        put("callID", JsonPrimitive("call-patch-1"))
                        put("name", JsonPrimitive("apply_patch"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-patch-1"))
                        put("input", buildJsonObject { put("patch", JsonPrimitive("*** Begin Patch")) })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-patch-updated",
                    type = "message.part.updated.1",
                    timestamp = 1_200L,
                    extra = buildJsonObject {
                        put(
                            "part",
                            buildJsonObject {
                                put("id", JsonPrimitive("patch-1"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("messageID", JsonPrimitive("assistant-1"))
                                put("type", JsonPrimitive("patch"))
                                put("hash", JsonPrimitive("hash-1"))
                                put(
                                    "files",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            JsonPrimitive("first.kt"),
                                            JsonPrimitive("second.kt"),
                                        ),
                                    ),
                                )
                            },
                        )
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val assistantUpdate = changes.last() as ReplayChange.Update
        val content = assistantUpdate.data["content"]!!.jsonArray

        assertThat(content).hasSize(2)
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool")
        assertThat(content[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("patch")
        assertThat(content[1].jsonObject["files"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("first.kt", "second.kt")
            .inOrder()
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
