package com.openmate.core.data.sync

import kotlinx.serialization.json.*
import com.openmate.core.database.entity.SessionMessageEntity

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(
        val id: String,
        val data: JsonObject,
        val timeUpdated: Long,
    ) : ReplayChange()
}

class EventReplayer {

    private data class ReplayState(
        val messages: MutableList<JsonObject> = mutableListOf(),
        var currentAssistantIndex: Int = -1,
        var currentCompactionIndex: Int = -1,
        val activeShells: MutableMap<String, Int> = mutableMapOf(),
    )

    fun replay(
        events: List<ReplayEvent>,
        sessionId: String,
        existingMessages: List<SessionMessageEntity> = emptyList(),
    ): List<ReplayChange> {
        val state = ReplayState()
        val changes = mutableListOf<ReplayChange>()

        if (existingMessages.isNotEmpty()) {
            val sorted = existingMessages.sortedByDescending { it.timeCreated }
            for ((index, entity) in sorted.withIndex()) {
                runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull()?.let { data ->
                    state.messages.add(data)
                    if (entity.type == "assistant" && data["time"]?.jsonObject?.containsKey("completed") != true) {
                        state.currentAssistantIndex = index
                    }
                    if (entity.type == "shell" && data["time"]?.jsonObject?.containsKey("completed") != true) {
                        data["callID"]?.jsonPrimitive?.contentOrNull?.let { callID ->
                            state.activeShells[callID] = index
                        }
                    }
                }
            }
        }

        for (event in events) {
            val props = event.data
            val timestamp = props["timestamp"]?.jsonPrimitive?.let { prim ->
                prim.longOrNull ?: runCatching { java.time.Instant.parse(prim.content).toEpochMilli() }.getOrNull()
            } ?: System.currentTimeMillis()
            val eventSessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId

            when (event.type.substringBeforeLast(".")) {
                "session.next.prompted" -> {
                    val text = props["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                    val files = props["prompt"]?.jsonObject?.get("files") ?: JsonArray(emptyList())
                    val agents = props["prompt"]?.jsonObject?.get("agents") ?: JsonArray(emptyList())
                    val msg = buildJsonObject {
                        put("type", "user")
                        put("id", event.id)
                        put("text", text)
                        put("files", files)
                        put("agents", agents)
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "user", msg, timestamp))
                }

                "session.next.agent.switched" -> {
                    val msg = buildJsonObject {
                        put("type", "agent-switched")
                        put("id", event.id)
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "agent-switched", msg, timestamp))
                }

                "session.next.model.switched" -> {
                    val model = props["model"]?.jsonObject ?: buildJsonObject {}
                    val msg = buildJsonObject {
                        put("type", "model-switched")
                        put("id", event.id)
                        put("model", model)
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "model-switched", msg, timestamp))
                }

                "session.next.synthetic" -> {
                    val msg = buildJsonObject {
                        put("type", "synthetic")
                        put("id", event.id)
                        put("sessionID", eventSessionId)
                        put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "synthetic", msg, timestamp))
                }

                "session.next.shell.started" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val msg = buildJsonObject {
                        put("type", "shell")
                        put("id", event.id)
                        put("callID", callID)
                        put("command", props["command"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("output", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    state.activeShells[callID] = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "shell", msg, timestamp))
                }

                "session.next.shell.ended" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val idx = state.activeShells.remove(callID) ?: continue
                    if (idx < state.messages.size) {
                        val updated = updateMessage(state, idx) { existing ->
                            existing["output"] = JsonPrimitive(props["output"]?.jsonPrimitive?.contentOrNull ?: "")
                            val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                            time["completed"] = JsonPrimitive(timestamp)
                            existing["time"] = JsonObject(time)
                        }
                        val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        changes += ReplayChange.Update(id, updated, timestamp)
                    }
                }

                "session.next.step.started" -> {
                    closeCurrentAssistant(state, changes, timestamp)
                    val snapshot = props["snapshot"]?.jsonPrimitive?.contentOrNull
                    val msg = buildJsonObject {
                        put("type", "assistant")
                        put("id", event.id)
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                        put("content", JsonArray(emptyList()))
                        if (snapshot != null) {
                            put("snapshot", buildJsonObject { put("start", snapshot) })
                        }
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    state.currentAssistantIndex = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "assistant", msg, timestamp))
                }

                "session.next.step.ended" -> {
                    val idx = state.currentAssistantIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMessage(state, idx) { existing ->
                        existing["finish"] = JsonPrimitive(props["finish"]?.jsonPrimitive?.contentOrNull ?: "")
                        props["cost"]?.jsonPrimitive?.let { existing["cost"] = it; Unit }
                        props["tokens"]?.jsonObject?.let { existing["tokens"] = it }
                        val snapEnd = props["snapshot"]?.jsonPrimitive?.contentOrNull
                        if (snapEnd != null) {
                            val snap = (existing["snapshot"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                            snap["end"] = JsonPrimitive(snapEnd)
                            existing["snapshot"] = JsonObject(snap)
                        }
                        val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        time["completed"] = JsonPrimitive(timestamp)
                        existing["time"] = JsonObject(time)
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }

                "session.next.step.failed" -> {
                    val idx = state.currentAssistantIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMessage(state, idx) { existing ->
                        existing["finish"] = JsonPrimitive("error")
                        props["error"]?.jsonObject?.let { existing["error"] = it }
                        val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        time["completed"] = JsonPrimitive(timestamp)
                        existing["time"] = JsonObject(time)
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }

                "session.next.text.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject { put("type", "text"); put("text", "") })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.text.ended" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: continue
                    val lastTextIdx = content.indexOfLast { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    if (lastTextIdx >= 0) {
                        val textObj = content[lastTextIdx].jsonObject.toMutableMap()
                        textObj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        content[lastTextIdx] = JsonObject(textObj)
                        assistant["content"] = JsonArray(content)
                    }
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.tool.input.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = props["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "tool")
                        put("id", callID)
                        put("name", name)
                        put("time", buildJsonObject { put("created", timestamp) })
                        put("state", buildJsonObject {
                            put("status", "pending")
                            put("input", "")
                        })
                    })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.tool.called" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["ran"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    toolObj["state"] = buildJsonObject {
                        put("status", "running")
                        put("input", props["input"] ?: JsonObject(emptyMap()))
                        put("structured", JsonObject(emptyMap()))
                        put("content", JsonArray(emptyList()))
                    }
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                }

                "session.next.tool.success" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    val prevInput = prevState["input"] ?: JsonObject(emptyMap())
                    toolObj["state"] = buildJsonObject {
                        put("status", "completed")
                        put("input", prevInput)
                        put("structured", props["structured"] ?: JsonObject(emptyMap()))
                        put("content", props["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.tool.failed" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    toolObj["state"] = buildJsonObject {
                        put("status", "error")
                        put("error", props["error"] ?: buildJsonObject {})
                        put("input", prevState["input"] ?: JsonObject(emptyMap()))
                        put("structured", prevState["structured"] ?: JsonObject(emptyMap()))
                        put("content", prevState["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.reasoning.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", reasoningID)
                        put("text", "")
                    })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.reasoning.ended" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val idx = content.indexOfLast {
                        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == reasoningID
                    }
                    if (idx >= 0) {
                        val mutableContent = content.toMutableList()
                        val obj = mutableContent[idx].jsonObject.toMutableMap()
                        obj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        mutableContent[idx] = JsonObject(obj)
                        assistant["content"] = JsonArray(mutableContent)
                    }
                }

                "session.next.compaction.started" -> {
                    val msg = buildJsonObject {
                        put("type", "compaction")
                        put("id", event.id)
                        put("reason", props["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                        put("summary", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    incrementAllIndices(state)
                    state.messages.add(0, msg)
                    state.currentCompactionIndex = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "compaction", msg, timestamp))
                }

                "session.next.compaction.ended" -> {
                    val idx = state.currentCompactionIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val updated = updateMessage(state, idx) { existing ->
                        existing["summary"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        props["include"]?.jsonPrimitive?.contentOrNull?.let { existing["include"] = JsonPrimitive(it) }
                    }
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }
            }
        }

        return changes
    }

    private fun incrementAllIndices(state: ReplayState) {
        if (state.currentAssistantIndex >= 0) state.currentAssistantIndex++
        if (state.currentCompactionIndex >= 0) state.currentCompactionIndex++
        val keys = state.activeShells.keys.toList()
        for (key in keys) {
            val current = state.activeShells[key] ?: continue
            state.activeShells[key] = current + 1
        }
    }

    private fun updateMessage(
        state: ReplayState,
        idx: Int,
        block: (MutableMap<String, JsonElement>) -> Unit,
    ): JsonObject {
        val existing = state.messages[idx].toMutableMap()
        block(existing)
        val updated = JsonObject(existing)
        state.messages[idx] = updated
        return updated
    }

    private fun closeCurrentAssistant(state: ReplayState, changes: MutableList<ReplayChange>, timestamp: Long) {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        if (msg["time"]?.jsonObject?.containsKey("completed") == false) {
            val updated = updateMessage(state, idx) { existing ->
                val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                time["completed"] = JsonPrimitive(timestamp)
                existing["time"] = JsonObject(time)
            }
            val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: return
            changes += ReplayChange.Update(id, updated, timestamp)
        }
    }

    private fun getMutableAssistant(state: ReplayState): MutableMap<String, JsonElement>? {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return null
        val existing = state.messages[idx].toMutableMap()
        state.messages[idx] = JsonObject(existing)
        return existing
    }

    private fun emitAssistantUpdate(state: ReplayState, changes: MutableList<ReplayChange>, timestamp: Long) {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return
        changes += ReplayChange.Update(id, msg as JsonObject, timestamp)
    }

    private fun findToolIndex(content: JsonArray, callID: String): Int? {
        return content.indexOfLast {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID
        }.takeIf { it >= 0 }
    }

    private fun msgToEntity(id: String, sessionId: String, type: String, data: JsonObject, timeCreated: Long): SessionMessageEntity {
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