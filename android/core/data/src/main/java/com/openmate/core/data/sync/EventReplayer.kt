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
        val type: String,
        val data: JsonObject,
        val timeUpdated: Long,
        val completedAt: Long? = null,
        val roundMark: Boolean? = null,
    ) : ReplayChange()
}

class EventReplayer {

    private var cachedId: String? = null
    private var cachedData: JsonObject? = null
    private var cachedType: String? = null
    private var cachedTimeCreated: Long = 0
    private var cachedRoundMark: Boolean = true

    private val activeShells = mutableMapOf<String, ShellEntry>()

    private data class ShellEntry(val id: String, val data: JsonObject, val timeCreated: Long)

    fun interface DbLoader {
        suspend operator fun invoke(action: Action): SessionMessageEntity?

        sealed class Action {
            data class LoadById(val id: String) : Action()
            data class LoadLatestIncompleteAssistant(val sessionId: String) : Action()
            data class LoadLatestIncompleteCompaction(val sessionId: String) : Action()
        }
    }

    suspend fun replay(
        events: List<ReplayEvent>,
        sessionId: String,
        loader: DbLoader,
    ): List<ReplayChange> {
        val changes = mutableListOf<ReplayChange>()

        ensureAssistantCache(sessionId, loader)

        for (event in events) {
            processEvent(event, sessionId, changes, loader)
        }

        return changes
    }

    private suspend fun ensureAssistantCache(sessionId: String, loader: DbLoader) {
        if (cachedType == "assistant") return
        val entity = loader(DbLoader.Action.LoadLatestIncompleteAssistant(sessionId)) ?: return
        val data = runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return
        cachedId = entity.id
        cachedType = entity.type
        cachedData = data
        cachedTimeCreated = entity.timeCreated
    }

    private suspend fun processEvent(
        event: ReplayEvent,
        sessionId: String,
        changes: MutableList<ReplayChange>,
        loader: DbLoader,
    ) {
        val props = event.data
        val timestamp = parseTimestamp(props)
        val eventType = event.type.substringBeforeLast(".")

        when (eventType) {
                "session.next.agent.switched" -> {
                    val data = buildJsonObject {
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "agent-switched", data, timestamp)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "agent-switched", data, timestamp))
                }

                "session.next.model.switched" -> {
                    val data = buildJsonObject {
                        put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "model-switched", data, timestamp)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "model-switched", data, timestamp))
                }

                "session.next.prompted" -> {
                    val userRoundMark = cachedType != "assistant" || cachedRoundMark
                    val data = buildJsonObject {
                        put("text", props["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "")
                        put("files", props["prompt"]?.jsonObject?.get("files") ?: JsonArray(emptyList()))
                        put("agents", props["prompt"]?.jsonObject?.get("agents") ?: JsonArray(emptyList()))
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "user", data, timestamp, roundMark = userRoundMark)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "user", data, timestamp, roundMark = userRoundMark))
                }

                "session.next.synthetic" -> {
                    val data = buildJsonObject {
                        put("sessionID", sessionId)
                        put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "synthetic", data, timestamp)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "synthetic", data, timestamp))
                }

                "session.next.shell.started" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val data = buildJsonObject {
                        put("callID", callID)
                        put("command", props["command"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("output", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    activeShells[callID] = ShellEntry(event.id, data, timestamp)
                    setCache(event.id, "shell", data, timestamp)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "shell", data, timestamp))
                }

                "session.next.shell.ended" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return
                    val shell = activeShells.remove(callID) ?: return
                    val updated = shell.data.toMutableMap()
                    updated["output"] = JsonPrimitive(props["output"]?.jsonPrimitive?.contentOrNull ?: "")
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    setCache(shell.id, "shell", merged, shell.timeCreated)
                    changes += ReplayChange.Update(shell.id, "shell", merged, timestamp)
                }

                "session.next.step.started" -> {
                    val cached = getCachedAssistant()
                    if (cached != null) {
                        val time = (cached.second["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        if (!time.containsKey("completed")) {
                            time["completed"] = JsonPrimitive(timestamp)
                        }
                        val merged = JsonObject(cached.second.toMutableMap().apply { put("time", JsonObject(time)) })
                        setCache(cached.first, "assistant", merged, cached.third)
                        changes += ReplayChange.Update(cached.first, "assistant", merged, timestamp, completedAt = timestamp)
                    }

                    val data = buildJsonObject {
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                        put("content", JsonArray(emptyList()))
                        val snapshot = props["snapshot"]?.jsonPrimitive?.contentOrNull
                        if (snapshot != null) {
                            put("snapshot", buildJsonObject { put("start", snapshot) })
                        }
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "assistant", data, timestamp, roundMark = false)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "assistant", data, timestamp, roundMark = false))
                }

                "session.next.step.ended" -> {
                    val cached = cachedAssistant() ?: return
                    val updated = cached.toMutableMap()
                    updated["finish"] = JsonPrimitive(props["finish"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["cost"]?.jsonPrimitive?.let { updated["cost"] = it }
                    props["tokens"]?.jsonObject?.let { updated["tokens"] = it }
                    val snapEnd = props["snapshot"]?.jsonPrimitive?.contentOrNull
                    if (snapEnd != null) {
                        val snap = (updated["snapshot"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        snap["end"] = JsonPrimitive(snapEnd)
                        updated["snapshot"] = JsonObject(snap)
                    }
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    val finish = props["finish"]?.jsonPrimitive?.contentOrNull
                    val roundMark = finish == "stop" || finish == "length"
                    cachedRoundMark = roundMark
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = roundMark)
                }

                "session.next.step.failed" -> {
                    val cached = cachedAssistant() ?: return
                    val updated = cached.toMutableMap()
                    updated["finish"] = JsonPrimitive("error")
                    props["error"]?.jsonObject?.let { updated["error"] = it }
                    val content = updated["content"]?.jsonArray
                    if (content != null) {
                        val mutableContent = content.map { item ->
                            val obj = item.jsonObject
                            if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool") return@map item
                            val state = obj["state"]?.jsonObject ?: return@map item
                            val status = state["status"]?.jsonPrimitive?.contentOrNull ?: return@map item
                            if (status != "pending" && status != "running") return@map item
                            JsonObject(obj.toMutableMap().apply {
                                put("state", buildJsonObject {
                                    put("status", "error")
                                    put("error", "Tool execution aborted")
                                    put("input", state["input"] ?: JsonObject(emptyMap()))
                                    put("structured", state["structured"] ?: JsonObject(emptyMap()))
                                    put("content", state["content"] ?: JsonArray(emptyList()))
                                    state["title"]?.jsonPrimitive?.contentOrNull?.let { put("title", it) }
                                    state["metadata"]?.jsonObject?.let {
                                        put("metadata", JsonObject(it.toMutableMap().apply {
                                            put("interrupted", JsonPrimitive(true))
                                        }))
                                    } ?: put("metadata", buildJsonObject { put("interrupted", true) })
                                })
                                val timeObj = (obj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                                timeObj["completed"] = JsonPrimitive(timestamp)
                                put("time", JsonObject(timeObj))
                            })
                        }
                        updated["content"] = JsonArray(mutableContent)
                    }
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    cachedRoundMark = true
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = true)
                }

                "session.next.text.started" -> {
                    val cached = cachedAssistant() ?: return
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject { put("type", "text"); put("text", "") })
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(content)
                    val merged = JsonObject(updated)
                    setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.text.ended" -> {
                    val cached = cachedAssistant() ?: return
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: return
                    val idx = content.indexOfLast { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    if (idx >= 0) {
                        val textObj = content[idx].jsonObject.toMutableMap()
                        textObj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        content[idx] = JsonObject(textObj)
                    }
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(content)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.input.started" -> {
                    val cached = cachedAssistant() ?: return
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return
                    val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
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
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(content)
                    val merged = JsonObject(updated)
                    setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.called" -> {
                    val cached = cachedAssistant() ?: return
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray ?: return
                    val toolIdx = findToolIndex(content, callID) ?: return
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
                        props["title"]?.jsonPrimitive?.contentOrNull?.let { put("title", it) }
                        props["metadata"]?.jsonObject?.let { put("metadata", it) }
                    }
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(mutableContent)
                    val merged = JsonObject(updated)
                    setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.success" -> {
                    val cached = cachedAssistant() ?: return
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray ?: return
                    val toolIdx = findToolIndex(content, callID) ?: return
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: return
                    val prevInput = prevState["input"] ?: JsonObject(emptyMap())
                    val prevTitle = prevState["title"]?.jsonPrimitive?.contentOrNull
                    val prevMetadata = prevState["metadata"]?.jsonObject
                    toolObj["state"] = buildJsonObject {
                        put("status", "completed")
                        put("input", prevInput)
                        put("structured", props["structured"] ?: JsonObject(emptyMap()))
                        put("content", props["content"] ?: JsonArray(emptyList()))
                        prevTitle?.let { put("title", it) }
                        prevMetadata?.let { put("metadata", it) }
                        props["title"]?.jsonPrimitive?.contentOrNull?.let { put("title", it) }
                        props["metadata"]?.jsonObject?.let { put("metadata", it) }
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(mutableContent)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.failed" -> {
                    val cached = cachedAssistant() ?: return
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray ?: return
                    val toolIdx = findToolIndex(content, callID) ?: return
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: return
                    val prevTitle = prevState["title"]?.jsonPrimitive?.contentOrNull
                    val prevMetadata = prevState["metadata"]?.jsonObject
                    toolObj["state"] = buildJsonObject {
                        put("status", "error")
                        put("error", props["error"] ?: buildJsonObject {})
                        put("input", prevState["input"] ?: JsonObject(emptyMap()))
                        put("structured", prevState["structured"] ?: JsonObject(emptyMap()))
                        put("content", prevState["content"] ?: JsonArray(emptyList()))
                        prevTitle?.let { put("title", it) }
                        prevMetadata?.let { put("metadata", it) }
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(mutableContent)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.reasoning.started" -> {
                    val cached = cachedAssistant() ?: return
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", reasoningID)
                        put("text", "")
                    })
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(content)
                    val merged = JsonObject(updated)
                    setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                    changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.reasoning.ended" -> {
                    val cached = cachedAssistant() ?: return
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return
                    val content = cached["content"]?.jsonArray ?: return
                    val idx = content.indexOfLast {
                        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == reasoningID
                    }
                    if (idx >= 0) {
                        val mutableContent = content.toMutableList()
                        val obj = mutableContent[idx].jsonObject.toMutableMap()
                        obj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        mutableContent[idx] = JsonObject(obj)
                        val updated = cached.toMutableMap()
                        updated["content"] = JsonArray(mutableContent)
                        val merged = JsonObject(updated)
                        updateCache(merged)
                        changes += ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                    }
                }

                "session.next.compaction.started" -> {
                    val data = buildJsonObject {
                        put("reason", props["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                        put("summary", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "compaction", data, timestamp)
                    changes += ReplayChange.Insert(entity(event.id, sessionId, "compaction", data, timestamp))
                }

                "session.next.compaction.ended" -> {
                    if (cachedType != "compaction") {
                        val existing = loader(DbLoader.Action.LoadLatestIncompleteCompaction(sessionId)) ?: return
                        val data = runCatching { Json.parseToJsonElement(existing.data).jsonObject }.getOrNull() ?: return
                        setCache(existing.id, "compaction", data, existing.timeCreated)
                    }
                    val cached = cachedData ?: return
                    val updated = cached.toMutableMap()
                    updated["summary"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["include"]?.jsonPrimitive?.contentOrNull?.let { updated["include"] = JsonPrimitive(it) }
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    changes += ReplayChange.Update(cachedId!!, "compaction", merged, timestamp, completedAt = timestamp)
                }
            }
    }

    private fun setCache(id: String, type: String, data: JsonObject, timeCreated: Long, roundMark: Boolean = true) {
        cachedId = id
        cachedType = type
        cachedData = data
        cachedTimeCreated = timeCreated
        cachedRoundMark = when (type) {
            "assistant" -> roundMark
            "user" -> roundMark
            else -> true
        }
    }

    private fun updateCache(data: JsonObject) {
        cachedData = data
    }

    private fun cachedAssistant(): JsonObject? {
        if (cachedType == "assistant") return cachedData
        return null
    }

    private data class CachedAssistant(val first: String, val second: JsonObject, val third: Long)

    private fun getCachedAssistant(): CachedAssistant? {
        if (cachedType == "assistant" && cachedId != null && cachedData != null) {
            return CachedAssistant(cachedId!!, cachedData!!, cachedTimeCreated)
        }
        return null
    }

    private fun findToolIndex(content: JsonArray, callID: String): Int? {
        return content.indexOfLast {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID
        }.takeIf { it >= 0 }
    }

    private fun parseTimestamp(props: JsonObject): Long {
        return props["timestamp"]?.jsonPrimitive?.let { prim ->
            prim.longOrNull ?: runCatching { java.time.Instant.parse(prim.content).toEpochMilli() }.getOrNull()
        } ?: System.currentTimeMillis()
    }

    private fun entity(id: String, sessionId: String, type: String, data: JsonObject, timeCreated: Long, roundMark: Boolean = true): SessionMessageEntity {
        return SessionMessageEntity(
            id = id,
            sessionId = sessionId,
            type = type,
            data = data.toString(),
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
            roundMark = roundMark,
        )
    }
}
