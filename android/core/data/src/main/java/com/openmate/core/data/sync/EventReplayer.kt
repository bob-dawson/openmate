package com.openmate.core.data.sync

import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.serialization.json.*

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(
        val id: String,
        val sessionId: String,
        val type: String,
        val data: JsonObject,
        val timeUpdated: Long,
    ) : ReplayChange()
}

class EventReplayer {

    private data class State(
        val messages: MutableList<JsonObject> = mutableListOf(),
        var currentAssistantIdx: Int = -1,
        var currentCompactionIdx: Int = -1,
        val activeShells: MutableMap<String, Int> = mutableMapOf(),
    )

    fun replay(events: List<ReplayEvent>, sessionId: String): List<ReplayChange> {
        val state = State()
        val changes = mutableListOf<ReplayChange>()

        for (event in events) {
            val p = event.data
            val ts = p["timestamp"]?.jsonPrimitive?.longOrNull ?: continue
            val baseType = event.type.substringBeforeLast(".")
            val sid = p["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId

            when (baseType) {
                "session.next.prompted" -> {
                    val msg = buildJsonObject {
                        put("type", "user")
                        put("id", event.id)
                        put("text", p["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "")
                        put("files", p["prompt"]?.jsonObject?.get("files") ?: JsonArray(emptyList()))
                        put("agents", p["prompt"]?.jsonObject?.get("agents") ?: JsonArray(emptyList()))
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "user", msg, ts))
                }

                "session.next.agent.switched" -> {
                    val msg = buildJsonObject {
                        put("type", "agent-switched")
                        put("id", event.id)
                        put("agent", p["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "agent-switched", msg, ts))
                }

                "session.next.model.switched" -> {
                    val model = p["model"]?.jsonObject ?: buildJsonObject {}
                    val msg = buildJsonObject {
                        put("type", "model-switched")
                        put("id", event.id)
                        put("model", model)
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "model-switched", msg, ts))
                }

                "session.next.synthetic" -> {
                    val msg = buildJsonObject {
                        put("type", "synthetic")
                        put("id", event.id)
                        put("sessionID", sid)
                        put("text", p["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "synthetic", msg, ts))
                }

                "session.next.shell.started" -> {
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val msg = buildJsonObject {
                        put("type", "shell")
                        put("id", event.id)
                        put("callID", callID)
                        put("command", p["command"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("output", "")
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    state.activeShells[callID] = 0
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "shell", msg, ts))
                }

                "session.next.shell.ended" -> {
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: continue
                    val idx = state.activeShells[callID] ?: continue
                    if (idx >= state.messages.size) continue
                    val updated = updateMsg(state, idx) { existing ->
                        existing["output"] = JsonPrimitive(p["output"]?.jsonPrimitive?.contentOrNull ?: "")
                        val time = existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                        time["completed"] = JsonPrimitive(ts)
                        existing["time"] = JsonObject(time)
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, sid, "shell", updated, ts)
                }

                "session.next.step.started" -> {
                    closeCurrentAssistant(state, changes, ts)
                    val snapshot = p["snapshot"]?.jsonPrimitive?.contentOrNull
                    val msg = buildJsonObject {
                        put("type", "assistant")
                        put("id", event.id)
                        put("agent", p["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("model", p["model"]?.jsonObject ?: buildJsonObject {})
                        put("content", JsonArray(emptyList()))
                        if (snapshot != null) put("snapshot", buildJsonObject { put("start", snapshot) })
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    state.currentAssistantIdx = 0
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "assistant", msg, ts))
                }

                "session.next.step.ended" -> {
                    val idx = state.currentAssistantIdx
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMsg(state, idx) { existing ->
                        existing["finish"] = JsonPrimitive(p["finish"]?.jsonPrimitive?.contentOrNull ?: "")
                        p["cost"]?.jsonPrimitive?.doubleOrNull?.let { existing["cost"] = JsonPrimitive(it) }
                        p["tokens"]?.jsonObject?.let { existing["tokens"] = it }
                        val snapEnd = p["snapshot"]?.jsonPrimitive?.contentOrNull
                        if (snapEnd != null) {
                            val snap = existing["snapshot"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                            snap["end"] = JsonPrimitive(snapEnd)
                            existing["snapshot"] = JsonObject(snap)
                        }
                        val time = existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                        time["completed"] = JsonPrimitive(ts)
                        existing["time"] = JsonObject(time)
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, sid, "assistant", updated, ts)
                }

                "session.next.step.failed" -> {
                    val idx = state.currentAssistantIdx
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMsg(state, idx) { existing ->
                        existing["finish"] = JsonPrimitive("error")
                        p["error"]?.jsonObject?.let { existing["error"] = it }
                        val time = existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                        time["completed"] = JsonPrimitive(ts)
                        existing["time"] = JsonObject(time)
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, sid, "assistant", updated, ts)
                }

                "session.next.text.started" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val content = asst["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject { put("type", "text"); put("text", "") })
                    asst["content"] = JsonArray(content)
                }

                "session.next.text.ended" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val content = asst["content"]?.jsonArray?.toMutableList() ?: continue
                    val lastTextIdx = content.indexOfLast { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    if (lastTextIdx >= 0) {
                        val textObj = content[lastTextIdx].jsonObject.toMutableMap()
                        textObj["text"] = JsonPrimitive(p["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        content[lastTextIdx] = JsonObject(textObj)
                        asst["content"] = JsonArray(content)
                    }
                    emitAssistantUpdate(state, changes, sid, ts)
                }

                "session.next.tool.input.started" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = p["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "tool")
                        put("id", callID)
                        put("name", name)
                        put("time", buildJsonObject { put("created", ts) })
                        put("state", buildJsonObject { put("status", "pending"); put("input", "") })
                    })
                    asst["content"] = JsonArray(content)
                }

                "session.next.tool.input.ended" -> { /* no-op */ }

                "session.next.tool.called" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIdx(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["ran"] = JsonPrimitive(ts)
                    toolObj["time"] = JsonObject(timeObj)
                    toolObj["provider"] = p["provider"]?.jsonObject ?: buildJsonObject {}
                    toolObj["state"] = buildJsonObject {
                        put("status", "running")
                        put("input", p["input"] ?: JsonObject(emptyMap()))
                        put("structured", JsonObject(emptyMap()))
                        put("content", JsonArray(emptyList()))
                    }
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    asst["content"] = JsonArray(mutableContent)
                }

                "session.next.tool.success" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIdx(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    val prevInput = prevState["input"] ?: JsonObject(emptyMap())
                    toolObj["state"] = buildJsonObject {
                        put("status", "completed")
                        put("input", prevInput)
                        put("structured", p["structured"] ?: JsonObject(emptyMap()))
                        put("content", p["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = p["provider"]?.jsonObject ?: buildJsonObject {}
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(ts)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    asst["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, sid, ts)
                }

                "session.next.tool.failed" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val callID = p["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIdx(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    toolObj["state"] = buildJsonObject {
                        put("status", "error")
                        put("error", p["error"] ?: buildJsonObject {})
                        put("input", prevState["input"] ?: JsonObject(emptyMap()))
                        put("structured", prevState["structured"] ?: JsonObject(emptyMap()))
                        put("content", prevState["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = p["provider"]?.jsonObject ?: buildJsonObject {}
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(ts)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    asst["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, sid, ts)
                }

                "session.next.reasoning.started" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val reasoningID = p["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", reasoningID)
                        put("text", "")
                    })
                    asst["content"] = JsonArray(content)
                }

                "session.next.reasoning.ended" -> {
                    val asst = getMutableAssistant(state) ?: continue
                    val reasoningID = p["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = asst["content"]?.jsonArray ?: continue
                    val idx = content.indexOfLast {
                        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == reasoningID
                    }
                    if (idx >= 0) {
                        val mutableContent = content.toMutableList()
                        val obj = mutableContent[idx].jsonObject.toMutableMap()
                        obj["text"] = JsonPrimitive(p["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        mutableContent[idx] = JsonObject(obj)
                        asst["content"] = JsonArray(mutableContent)
                    }
                }

                "session.next.compaction.started" -> {
                    val msg = buildJsonObject {
                        put("type", "compaction")
                        put("id", event.id)
                        put("reason", p["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                        put("summary", "")
                        put("time", buildJsonObject { put("created", ts) })
                    }
                    state.messages.add(0, msg)
                    state.currentCompactionIdx = 0
                    changes += ReplayChange.Insert(toEntity(event.id, sid, "compaction", msg, ts))
                }

                "session.next.compaction.ended" -> {
                    val idx = state.currentCompactionIdx
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMsg(state, idx) { existing ->
                        existing["summary"] = JsonPrimitive(p["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        p["include"]?.jsonPrimitive?.contentOrNull?.let { existing["include"] = JsonPrimitive(it) }
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, sid, "compaction", updated, ts)
                }
            }
        }

        return changes
    }

    private fun closeCurrentAssistant(state: State, changes: MutableList<ReplayChange>, ts: Long) {
        val idx = state.currentAssistantIdx
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        if (msg["time"]?.jsonObject?.containsKey("completed") == false) {
            val updated = updateMsg(state, idx) { existing ->
                val time = existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                time["completed"] = JsonPrimitive(ts)
                existing["time"] = JsonObject(time)
            }
            val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: return
            val sid = updated["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
            changes += ReplayChange.Update(id, sid, "assistant", updated, ts)
        }
    }

    private fun getMutableAssistant(state: State): MutableMap<String, JsonElement>? {
        val idx = state.currentAssistantIdx
        if (idx < 0 || idx >= state.messages.size) return null
        val existing = state.messages[idx].toMutableMap()
        state.messages[idx] = JsonObject(existing)
        return existing
    }

    private fun emitAssistantUpdate(
        state: State,
        changes: MutableList<ReplayChange>,
        sessionId: String,
        ts: Long,
    ) {
        val idx = state.currentAssistantIdx
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return
        changes += ReplayChange.Update(id, sessionId, "assistant", msg as JsonObject, ts)
    }

    private fun findToolIdx(content: JsonArray, callID: String): Int? {
        return content.indexOfLast {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
                it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID
        }.takeIf { it >= 0 }
    }

    private fun updateMsg(
        state: State,
        idx: Int,
        block: (MutableMap<String, JsonElement>) -> Unit,
    ): JsonObject {
        val existing = state.messages[idx].toMutableMap()
        block(existing)
        val updated = JsonObject(existing)
        state.messages[idx] = updated
        return updated
    }

    private fun toEntity(
        id: String,
        sessionId: String,
        type: String,
        data: JsonObject,
        timeCreated: Long,
    ): SessionMessageEntity {
        return SessionMessageEntity(
            id = id,
            sessionId = sessionId,
            type = type,
            data = data.toString(),
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
        )
    }
}
