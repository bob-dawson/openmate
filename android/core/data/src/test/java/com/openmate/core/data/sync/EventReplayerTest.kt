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
                    is EventReplayer.DbLoader.Action.LoadLatestAssistant -> null
                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction -> existing
                    is EventReplayer.DbLoader.Action.LoadAssistantByToolCallId -> null
                    is EventReplayer.DbLoader.Action.FindLatestUserMessage -> null
                    is EventReplayer.DbLoader.Action.HasNewerUserMessageAfter -> null
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
    fun replay_messagePartUpdated_mergesToolMetadataForRunningTask() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "assistant-1",
                    type = "session.next.step.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("assistantMessageID", JsonPrimitive("assistant-1"))
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
                        put("input", buildJsonObject {
                            put("description", JsonPrimitive("Test task"))
                            put("prompt", JsonPrimitive("do something"))
                            put("subagent_type", JsonPrimitive("general"))
                        })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-part-updated",
                    type = "message.part.updated.1",
                    timestamp = 1_150L,
                    extra = buildJsonObject {
                        put(
                            "part",
                            buildJsonObject {
                                put("id", JsonPrimitive("prt-1"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("messageID", JsonPrimitive("msg-different-id"))
                                put("type", JsonPrimitive("tool"))
                                put("callID", JsonPrimitive("call-task-1"))
                                put("state", buildJsonObject {
                                    put("status", JsonPrimitive("running"))
                                    put("metadata", buildJsonObject {
                                        put("sessionId", JsonPrimitive("ses_subtask123"))
                                    })
                                    put("title", JsonPrimitive("Test task"))
                                })
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
        val tool = content.single().jsonObject

        val state = tool["state"]!!.jsonObject
        assertThat(state["status"]!!.jsonPrimitive.content).isEqualTo("running")
        assertThat(state["metadata"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
            .isEqualTo("ses_subtask123")
        assertThat(state["title"]!!.jsonPrimitive.content).isEqualTo("Test task")
    }

    @Test
    fun replay_messagePartUpdated_keepsExistingToolStateFieldsWhenMerging() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "assistant-1",
                    type = "session.next.step.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("assistantMessageID", JsonPrimitive("assistant-1"))
                        put("agent", JsonPrimitive("build"))
                        put("model", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-input-started",
                    type = "session.next.tool.input.started.1",
                    timestamp = 1_050L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-task-2"))
                        put("name", JsonPrimitive("task"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-task-2"))
                        put("input", buildJsonObject {
                            put("description", JsonPrimitive("My task"))
                            put("prompt", JsonPrimitive("do it"))
                            put("subagent_type", JsonPrimitive("general"))
                        })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-part-updated",
                    type = "message.part.updated.1",
                    timestamp = 1_150L,
                    extra = buildJsonObject {
                        put(
                            "part",
                            buildJsonObject {
                                put("id", JsonPrimitive("prt-2"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("messageID", JsonPrimitive("msg-also-different-id"))
                                put("type", JsonPrimitive("tool"))
                                put("callID", JsonPrimitive("call-task-2"))
                                put("state", buildJsonObject {
                                    put("status", JsonPrimitive("running"))
                                    put("metadata", buildJsonObject {
                                        put("sessionId", JsonPrimitive("ses_child456"))
                                        put("model", buildJsonObject {
                                            put("modelID", JsonPrimitive("gpt-5.1"))
                                        })
                                    })
                                    put("title", JsonPrimitive("My task"))
                                })
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
        val tool = content.single().jsonObject
        val state = tool["state"]!!.jsonObject

        assertThat(state["status"]!!.jsonPrimitive.content).isEqualTo("running")
        assertThat(state["input"]!!.jsonObject["description"]!!.jsonPrimitive.content)
            .isEqualTo("My task")
        assertThat(state["metadata"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
            .isEqualTo("ses_child456")
        assertThat(state["metadata"]!!.jsonObject["model"]!!.jsonObject["modelID"]!!.jsonPrimitive.content)
            .isEqualTo("gpt-5.1")
        assertThat(state["title"]!!.jsonPrimitive.content).isEqualTo("My task")
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

    @Test
    fun replay_toolProgress_updatesStructuredForRunningTool() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "assistant-1",
                    type = "session.next.step.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("assistantMessageID", JsonPrimitive("assistant-1"))
                        put("agent", JsonPrimitive("build"))
                        put("model", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-input-started",
                    type = "session.next.tool.input.started.1",
                    timestamp = 1_050L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-1"))
                        put("name", JsonPrimitive("task"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-1"))
                        put("input", buildJsonObject {
                            put("description", JsonPrimitive("My task"))
                            put("prompt", JsonPrimitive("do it"))
                            put("subagent_type", JsonPrimitive("general"))
                        })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-progress",
                    type = "session.next.tool.progress.1",
                    timestamp = 1_200L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-1"))
                        put("structured", buildJsonObject {
                            put("sessionId", JsonPrimitive("ses_progress_child"))
                            put("model", buildJsonObject {
                                put("modelID", JsonPrimitive("gpt-5.1"))
                            })
                        })
                        put("content", kotlinx.serialization.json.JsonArray(listOf(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive("partial output"))
                            }
                        )))
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val assistantUpdate = changes.last() as ReplayChange.Update
        val content = assistantUpdate.data["content"]!!.jsonArray
        val tool = content.single().jsonObject
        val state = tool["state"]!!.jsonObject

        assertThat(state["status"]!!.jsonPrimitive.content).isEqualTo("running")
        assertThat(state["structured"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
            .isEqualTo("ses_progress_child")
        assertThat(state["structured"]!!.jsonObject["model"]!!.jsonObject["modelID"]!!.jsonPrimitive.content)
            .isEqualTo("gpt-5.1")
        assertThat(state["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
            .isEqualTo("partial output")
    }

    @Test
    fun replay_toolProgress_ignoresCompletedTool() = runTest {
        val replayer = EventReplayer()

        val changes = replayer.replay(
            events = listOf(
                replayEvent(
                    id = "assistant-1",
                    type = "session.next.step.started.1",
                    timestamp = 1_000L,
                    extra = buildJsonObject {
                        put("assistantMessageID", JsonPrimitive("assistant-1"))
                        put("agent", JsonPrimitive("build"))
                        put("model", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-input-started",
                    type = "session.next.tool.input.started.1",
                    timestamp = 1_050L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-2"))
                        put("name", JsonPrimitive("bash"))
                    },
                ),
                replayEvent(
                    id = "evt-tool-called",
                    type = "session.next.tool.called.1",
                    timestamp = 1_100L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-2"))
                        put("input", buildJsonObject { put("cmd", "pwd") })
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-success",
                    type = "session.next.tool.success.1",
                    timestamp = 1_150L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-2"))
                        put("structured", buildJsonObject { put("exitCode", JsonPrimitive(0)) })
                        put("content", kotlinx.serialization.json.JsonArray(emptyList()))
                        put("provider", buildJsonObject {})
                    },
                ),
                replayEvent(
                    id = "evt-tool-progress-late",
                    type = "session.next.tool.progress.1",
                    timestamp = 1_200L,
                    extra = buildJsonObject {
                        put("callID", JsonPrimitive("call-prog-2"))
                        put("structured", buildJsonObject {
                            put("sessionId", JsonPrimitive("should_not_appear"))
                        })
                        put("content", kotlinx.serialization.json.JsonArray(emptyList()))
                    },
                ),
            ),
            sessionId = "session-1",
            loader = EventReplayer.DbLoader { null },
        )

        val assistantUpdate = changes.last() as ReplayChange.Update
        val content = assistantUpdate.data["content"]!!.jsonArray
        val tool = content.single().jsonObject
        val state = tool["state"]!!.jsonObject

        assertThat(state["status"]!!.jsonPrimitive.content).isEqualTo("completed")
        val sessionIdField = state["structured"]!!.jsonObject["sessionId"]
        assertThat(sessionIdField).isNull()
    }
}
