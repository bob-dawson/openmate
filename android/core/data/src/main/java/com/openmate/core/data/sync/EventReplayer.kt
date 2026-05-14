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
    data class Delete(val id: String) : ReplayChange()
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
            data class LoadAssistantByToolCallId(val sessionId: String, val callID: String) : Action()
        }
    }

    suspend fun processEvent(
        event: ReplayEvent,
        sessionId: String,
        loader: DbLoader,
    ): ReplayChange? {
        ensureAssistantCache(sessionId, loader)
        return processEventInternal(event, sessionId, loader)
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

    private suspend fun processEventInternal(
        event: ReplayEvent,
        sessionId: String,
        loader: DbLoader,
    ) : ReplayChange?  {
        val props = event.data
        val timestamp = parseTimestamp(props)
        val eventType = event.type

        when (eventType) {
                "session.next.agent.switched" -> {
                    val data = buildJsonObject {
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    return ReplayChange.Insert(entity(event.id, sessionId, "agent-switched", data, timestamp))
                }

                "session.next.model.switched" -> {
                    val data = buildJsonObject {
                        put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    return ReplayChange.Insert(entity(event.id, sessionId, "model-switched", data, timestamp))
                }

                "session.next.prompted" -> {
                    val userRoundMark = cachedType != "assistant" || cachedRoundMark
                    val data = buildJsonObject {
                        put("text", props["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "")
                        put("files", props["prompt"]?.jsonObject?.get("files") ?: JsonArray(emptyList()))
                        put("agents", props["prompt"]?.jsonObject?.get("agents") ?: JsonArray(emptyList()))
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    return ReplayChange.Insert(entity(event.id, sessionId, "user", data, timestamp, roundMark = userRoundMark))
                }

                "session.next.synthetic" -> {
                    val data = buildJsonObject {
                        put("sessionID", sessionId)
                        put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    return ReplayChange.Insert(entity(event.id, sessionId, "synthetic", data, timestamp))
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
                    return ReplayChange.Insert(entity(event.id, sessionId, "shell", data, timestamp))
                }

                "session.next.shell.ended" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val shell = activeShells.remove(callID) ?: return null
                    val updated = shell.data.toMutableMap()
                    updated["output"] = JsonPrimitive(props["output"]?.jsonPrimitive?.contentOrNull ?: "")
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    setCache(shell.id, "shell", merged, shell.timeCreated)
                    return ReplayChange.Update(shell.id, "shell", merged, timestamp)
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
                        return ReplayChange.Update(cached.first, "assistant", merged, timestamp, completedAt = timestamp)
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
                    return ReplayChange.Insert(entity(event.id, sessionId, "assistant", data, timestamp, roundMark = false))
                }

                "session.next.step.ended" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = roundMark)
                }

                "session.next.step.failed" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = true)
                }

                "session.next.text.started" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject { put("type", "text"); put("text", "") })
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(content)
                    val merged = JsonObject(updated)
                    setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.text.ended" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val content = cached["content"]?.jsonArray?.toMutableList() ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.input.started" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.called" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val content = cached["content"]?.jsonArray ?: return null
                    val toolIdx = findToolIndex(content, callID) ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.progress" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val content = cached["content"]?.jsonArray ?: return null
                    val toolIdx = findToolIndex(content, callID) ?: return null
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: return null
                    if (prevState["status"]?.jsonPrimitive?.contentOrNull != "running") return null
                    val mergedState = prevState.toMutableMap()
                    props["structured"]?.jsonObject?.let { mergedState["structured"] = it }
                    props["content"]?.jsonArray?.let { mergedState["content"] = it }
                    toolObj["state"] = JsonObject(mergedState)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(mutableContent)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.success" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val content = cached["content"]?.jsonArray ?: return null
                    val toolIdx = findToolIndex(content, callID) ?: return null
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.tool.failed" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val content = cached["content"]?.jsonArray ?: return null
                    val toolIdx = findToolIndex(content, callID) ?: return null
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "message.part.updated" -> {
                    val part = props["part"]?.jsonObject ?: return null
                    val partType = part["type"]?.jsonPrimitive?.contentOrNull ?: return null
                    if (partType == "patch") {
                        val cached = cachedAssistant() ?: return null
                        val messageId = part["messageID"]?.jsonPrimitive?.contentOrNull ?: return null
                        if (messageId != cachedId) return null
                        val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                        content.add(part)
                        val updated = cached.toMutableMap()
                        updated["content"] = JsonArray(content)
                        val merged = JsonObject(updated)
                        updateCache(merged)
                        return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                    } else if (partType == "tool") {
                        val partState = part["state"]?.jsonObject ?: return null
                        val callID = part["callID"]?.jsonPrimitive?.contentOrNull
                            ?: part["id"]?.jsonPrimitive?.contentOrNull
                            ?: return null
                        val cached = cachedAssistant()
                        val (targetData, targetId) = if (cached != null) {
                            val content = cached["content"]?.jsonArray
                            val toolIdx = content?.indexOfLast {
                                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
                                (it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID ||
                                 it.jsonObject["callID"]?.jsonPrimitive?.contentOrNull == callID)
                            } ?: -1
                            if (toolIdx >= 0) Pair(cached, cachedId!!) else {
                                val loaded = findToolInDb(callID, sessionId, loader) ?: return null
                                Pair(loaded.first, loaded.second)
                            }
                        } else {
                            val loaded = findToolInDb(callID, sessionId, loader) ?: return null
                            Pair(loaded.first, loaded.second)
                        }
                        val content = targetData["content"]?.jsonArray ?: return null
                        val toolIdx = content.indexOfLast {
                            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
                            (it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID ||
                             it.jsonObject["callID"]?.jsonPrimitive?.contentOrNull == callID)
                        }
                        if (toolIdx < 0) return null
                        val mutableContent = content.toMutableList()
                        val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                        val prevState = toolObj["state"]?.jsonObject
                        if (prevState != null) {
                            val mergedState = prevState.toMutableMap()
                            val newMeta = partState["metadata"]?.jsonObject
                            if (newMeta != null) {
                                val prevMeta = prevState["metadata"]?.jsonObject
                                val prevSid = prevMeta?.let { m -> (m["sessionId"] ?: m["sessionID"])?.jsonPrimitive?.contentOrNull }
                                val newSid = (newMeta["sessionId"] ?: newMeta["sessionID"])?.jsonPrimitive?.contentOrNull
                                if (prevSid != null && newSid == null) {
                                    val mergedMeta = newMeta.toMutableMap()
                                    mergedMeta["sessionId"] = JsonPrimitive(prevSid)
                                    mergedState["metadata"] = JsonObject(mergedMeta)
                                } else {
                                    mergedState["metadata"] = newMeta
                                }
                            }
                            partState["title"]?.jsonPrimitive?.let { mergedState["title"] = it }
                            partState["structured"]?.jsonObject?.let { mergedState["structured"] = it }
                            partState["content"]?.jsonArray?.let { mergedState["content"] = it }
                            toolObj["state"] = JsonObject(mergedState)
                        } else {
                            toolObj["state"] = partState
                        }
                        mutableContent[toolIdx] = JsonObject(toolObj)
                        val updated = targetData.toMutableMap()
                        updated["content"] = JsonArray(mutableContent)
                        val merged = JsonObject(updated)
                        if (targetId == cachedId) {
                            updateCache(merged)
                        }
                        return ReplayChange.Update(targetId, "assistant", merged, timestamp)
                    }
                }

                "session.next.reasoning.started" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return null
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
                    return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                }

                "session.next.reasoning.ended" -> {
                    val cached = ensureCachedAssistant(sessionId, loader) ?: return null
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val content = cached["content"]?.jsonArray ?: return null
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
                        return ReplayChange.Update(cachedId!!, "assistant", merged, timestamp)
                    }
                }

                "session.next.compaction.started" -> {
                    val data = buildJsonObject {
                        put("reason", props["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                        put("summary", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    setCache(event.id, "compaction", data, timestamp)
                    return ReplayChange.Insert(entity(event.id, sessionId, "compaction", data, timestamp))
                }

                "session.next.compaction.ended" -> {
                    if (cachedType != "compaction") {
                        val existing = loader(DbLoader.Action.LoadLatestIncompleteCompaction(sessionId)) ?: return null
                        val data = runCatching { Json.parseToJsonElement(existing.data).jsonObject }.getOrNull() ?: return null
                        setCache(existing.id, "compaction", data, existing.timeCreated)
                    }
                    val cached = cachedData ?: return null
                    val updated = cached.toMutableMap()
                    updated["summary"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["include"]?.jsonPrimitive?.contentOrNull?.let { updated["include"] = JsonPrimitive(it) }
                    val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    updated["time"] = JsonObject(time)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    return ReplayChange.Update(cachedId!!, "compaction", merged, timestamp, completedAt = timestamp)
                }

                "message.removed" -> {
                    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return null
                    return ReplayChange.Delete(messageId)
                }

                "message.part.removed" -> {
                    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val partId = props["partID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val entity = loader(DbLoader.Action.LoadById(messageId)) ?: return null
                    val data = runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return null
                    val content = data["content"]?.jsonArray ?: return null
                    val filtered = content.filterNot { part ->
                        part.jsonObject["id"]?.jsonPrimitive?.contentOrNull == partId
                    }
                    if (filtered.isEmpty()) {
                        return ReplayChange.Delete(messageId)
                    } else {
                        val updated = data.toMutableMap()
                        updated["content"] = JsonArray(filtered)
                        return ReplayChange.Update(messageId, entity.type, JsonObject(updated), entity.timeUpdated)
                    }
                }
            }
        return null
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

    private suspend fun ensureCachedAssistant(sessionId: String, loader: DbLoader): JsonObject? {
        if (cachedType == "assistant") return cachedData
        ensureAssistantCache(sessionId, loader)
        return cachedData?.takeIf { cachedType == "assistant" }
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

    private suspend fun findToolInDb(
        callID: String,
        sessionId: String,
        loader: DbLoader,
    ): Pair<JsonObject, String>? {
        val entity = loader(DbLoader.Action.LoadAssistantByToolCallId(sessionId, callID)) ?: return null
        val data = runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return null
        return Pair(data, entity.id)
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
