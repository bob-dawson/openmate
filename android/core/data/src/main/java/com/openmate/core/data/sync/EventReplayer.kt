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
    private var pendingCompactionId: String? = null

    fun cacheDebugInfo(): String = "id=${cachedId?.take(20)} type=$cachedType hasCompleted=${cachedData?.get("time")?.jsonObject?.containsKey("completed")}"

    private data class ShellEntry(val id: String, val data: JsonObject, val timeCreated: Long)

    fun interface DbLoader {
        suspend operator fun invoke(action: Action): SessionMessageEntity?

        sealed class Action {
            data class LoadById(val id: String) : Action()
            data class LoadLatestIncompleteAssistant(val sessionId: String) : Action()
            data class LoadLatestAssistant(val sessionId: String) : Action()
            data class LoadLatestIncompleteCompaction(val sessionId: String) : Action()
            data class LoadAssistantByToolCallId(val sessionId: String, val callID: String) : Action()
            data class FindLatestUserMessage(val sessionId: String) : Action()
            data class HasNewerUserMessageAfter(val sessionId: String, val afterTimeCreated: Long) : Action()
        }
    }

    suspend fun processEvent(
        event: ReplayEvent,
        sessionId: String,
        loader: DbLoader,
    ): List<ReplayChange> {
        return processEventInternal(event, sessionId, loader)
    }

    private suspend fun ensureAssistantCache(sessionId: String, loader: DbLoader) {
        if (cachedType == "assistant") return
        val entity = loader(DbLoader.Action.LoadLatestIncompleteAssistant(sessionId))
            ?: loader(DbLoader.Action.LoadLatestAssistant(sessionId))
            ?: return
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
    ) : List<ReplayChange>  {
        val props = event.data
        val timestamp = parseTimestamp(props)
        val eventType = event.type

        when (eventType) {
            "session.next.agent.switched" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val data = buildJsonObject {
                    put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                setCache(msgId, "agent-switched", data, timestamp)
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "agent-switched", data, timestamp)))
            }

            "session.next.model.switched" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val data = buildJsonObject {
                    put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                setCache(msgId, "model-switched", data, timestamp)
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "model-switched", data, timestamp)))
            }

            "session.next.prompted" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val userRoundMark = cachedType != "assistant" || cachedRoundMark
                val prompt = props["prompt"]?.jsonObject
                val dataMap = mutableMapOf<String, JsonElement>(
                    "text" to (prompt?.get("text")?.jsonPrimitive ?: JsonPrimitive("")),
                    "files" to (prompt?.get("files") ?: JsonArray(emptyList())),
                    "agents" to (prompt?.get("agents") ?: JsonArray(emptyList())),
                    "time" to buildJsonObject { put("created", timestamp) },
                )
                prompt?.get("references")?.let { dataMap["references"] = it }
                val data = JsonObject(dataMap)
                setCache(msgId, "user", data, timestamp, roundMark = userRoundMark)
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "user", data, timestamp, roundMark = userRoundMark)))
            }

            "session.next.context.updated" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val data = buildJsonObject {
                    put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "system", data, timestamp)))
            }

            "session.next.synthetic" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val data = buildJsonObject {
                    put("sessionID", sessionId)
                    put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "synthetic", data, timestamp)))
            }

            "session.next.shell.started" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                val data = buildJsonObject {
                    put("callID", callID)
                    put("command", props["command"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("output", "")
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                activeShells[callID] = ShellEntry(msgId, data, timestamp)
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "shell", data, timestamp)))
            }

            "session.next.shell.ended" -> {
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val shell = activeShells.remove(callID) ?: return emptyList()
                val updated = shell.data.toMutableMap()
                updated["output"] = JsonPrimitive(props["output"]?.jsonPrimitive?.contentOrNull ?: "")
                val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                time["completed"] = JsonPrimitive(timestamp)
                updated["time"] = JsonObject(time)
                val merged = JsonObject(updated)
                setCache(shell.id, "shell", merged, shell.timeCreated)
                return listOf(ReplayChange.Update(shell.id, "shell", merged, timestamp))
            }

            "session.next.step.started" -> {
                val assistantMsgId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                ensureCachedAssistant(sessionId, loader)
                val pendingCompletion: ReplayChange.Update? = if (cachedType == "assistant" && cachedData != null && cachedId != null) {
                    val timeObj = cachedData!!["time"]?.jsonObject
                    val alreadyCompleted = timeObj?.containsKey("completed") == true
                    if (!alreadyCompleted) {
                        val time = (timeObj?.toMutableMap() ?: mutableMapOf())
                        time["completed"] = JsonPrimitive(timestamp)
                        val merged = JsonObject(cachedData!!.toMutableMap().apply { put("time", JsonObject(time)) })
                        setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                        ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp)
                    } else {
                        null
                    }
                } else {
                    null
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
                setCache(assistantMsgId, "assistant", data, timestamp, roundMark = false)
                val insert = ReplayChange.Insert(entity(assistantMsgId, sessionId, "assistant", data, timestamp, roundMark = false))
                return if (pendingCompletion != null) listOf(pendingCompletion, insert) else listOf(insert)
            }

            "session.next.step.ended" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
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
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = roundMark))
            }

            "session.next.step.failed" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val updated = cached.toMutableMap()
                updated["finish"] = JsonPrimitive("error")
                props["error"]?.jsonObject?.let { updated["error"] = it }
                val time = (updated["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                time["completed"] = JsonPrimitive(timestamp)
                updated["time"] = JsonObject(time)
                val merged = JsonObject(updated)
                updateCache(merged)
                cachedRoundMark = true
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp, completedAt = timestamp, roundMark = true))
            }

            "session.next.text.started" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val textID = props["textID"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                content.add(buildJsonObject {
                    put("type", "text")
                    put("id", textID)
                    put("text", "")
                })
                val updated = cached.toMutableMap()
                updated["content"] = JsonArray(content)
                val merged = JsonObject(updated)
                setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.text.ended" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val content = cached["content"]?.jsonArray?.toMutableList() ?: return emptyList()
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
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.tool.input.started" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
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
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.tool.called" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray ?: return emptyList()
                val toolIdx = findToolIndex(content, callID) ?: return emptyList()
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
                val updated = cached.toMutableMap()
                updated["content"] = JsonArray(mutableContent)
                val merged = JsonObject(updated)
                setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.tool.progress" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray ?: return emptyList()
                val toolIdx = findToolIndex(content, callID) ?: return emptyList()
                val mutableContent = content.toMutableList()
                val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                val prevState = toolObj["state"]?.jsonObject ?: return emptyList()
                if (prevState["status"]?.jsonPrimitive?.contentOrNull != "running") return emptyList()
                val mergedState = prevState.toMutableMap()
                props["structured"]?.jsonObject?.let { mergedState["structured"] = it }
                props["content"]?.jsonArray?.let { mergedState["content"] = it }
                toolObj["state"] = JsonObject(mergedState)
                mutableContent[toolIdx] = JsonObject(toolObj)
                val updated = cached.toMutableMap()
                updated["content"] = JsonArray(mutableContent)
                val merged = JsonObject(updated)
                updateCache(merged)
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.tool.success" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray ?: return emptyList()
                val toolIdx = findToolIndex(content, callID) ?: return emptyList()
                val mutableContent = content.toMutableList()
                val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                val prevState = toolObj["state"]?.jsonObject ?: return emptyList()
                val prevInput = prevState["input"] ?: JsonObject(emptyMap())
                val stateMap = mutableMapOf<String, JsonElement>(
                    "status" to JsonPrimitive("completed"),
                    "input" to prevInput,
                    "structured" to (props["structured"] ?: JsonObject(emptyMap())),
                    "content" to (props["content"] ?: JsonArray(emptyList())),
                    "outputPaths" to (props["outputPaths"]?.jsonArray ?: JsonArray(emptyList())),
                )
                props["result"]?.let { stateMap["result"] = it }
                toolObj["state"] = JsonObject(stateMap)
                toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                timeObj["completed"] = JsonPrimitive(timestamp)
                toolObj["time"] = JsonObject(timeObj)
                mutableContent[toolIdx] = JsonObject(toolObj)
                val updated = cached.toMutableMap()
                updated["content"] = JsonArray(mutableContent)
                val merged = JsonObject(updated)
                updateCache(merged)
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.tool.failed" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray ?: return emptyList()
                val toolIdx = findToolIndex(content, callID) ?: return emptyList()
                val mutableContent = content.toMutableList()
                val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                val prevState = toolObj["state"]?.jsonObject ?: return emptyList()
                toolObj["state"] = buildJsonObject {
                    put("status", "error")
                    put("error", props["error"] ?: buildJsonObject {})
                    put("input", prevState["input"] ?: JsonObject(emptyMap()))
                    put("structured", prevState["structured"] ?: JsonObject(emptyMap()))
                    put("content", prevState["content"] ?: JsonArray(emptyList()))
                    props["result"]?.let { put("result", it) }
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
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.reasoning.started" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                content.add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", reasoningID)
                    put("text", "")
                    props["providerMetadata"]?.jsonObject?.let { put("providerMetadata", it) }
                })
                val updated = cached.toMutableMap()
                updated["content"] = JsonArray(content)
                val merged = JsonObject(updated)
                setCache(cachedId!!, "assistant", merged, cachedTimeCreated)
                return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
            }

            "session.next.reasoning.ended" -> {
                val cached = ensureCachedAssistant(sessionId, loader) ?: return emptyList()
                val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val content = cached["content"]?.jsonArray ?: return emptyList()
                val idx = content.indexOfLast {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                    it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == reasoningID
                }
                if (idx >= 0) {
                    val mutableContent = content.toMutableList()
                    val obj = mutableContent[idx].jsonObject.toMutableMap()
                    obj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["providerMetadata"]?.jsonObject?.let { obj["providerMetadata"] = it }
                    mutableContent[idx] = JsonObject(obj)
                    val updated = cached.toMutableMap()
                    updated["content"] = JsonArray(mutableContent)
                    val merged = JsonObject(updated)
                    updateCache(merged)
                    return listOf(ReplayChange.Update(cachedId!!, "assistant", merged, timestamp))
                }
            }

            "session.next.compaction.started" -> {
                props["messageID"]?.jsonPrimitive?.contentOrNull?.let { mid ->
                    pendingCompactionId = mid
                }
                return emptyList()
            }

            "session.next.compaction.ended" -> {
                val msgId = props["messageID"]?.jsonPrimitive?.contentOrNull
                    ?: pendingCompactionId
                    ?: return emptyList()
                pendingCompactionId = null
                val data = buildJsonObject {
                    put("reason", props["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                    put("summary", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["recent"]?.jsonPrimitive?.contentOrNull?.let { put("recent", it) }
                    props["include"]?.jsonPrimitive?.contentOrNull?.let { put("include", it) }
                    put("time", buildJsonObject { put("created", timestamp) })
                }
                setCache(msgId, "compaction", data, timestamp)
                return listOf(ReplayChange.Insert(entity(msgId, sessionId, "compaction", data, timestamp)))
            }

            "message.removed" -> {
                val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                return listOf(ReplayChange.Delete(messageId))
            }

            "session.next.retried",
            "session.next.prompt.admitted",
            "session.next.prompt.promoted",
            "session.next.interrupt.requested",
            "session.next.compaction.delta",
            "session.next.moved",
            "session.next.tool.input.delta",
            "session.next.text.delta",
            "session.next.reasoning.delta",
            "message.updated",
            "message.part.updated",
            "message.part.removed" -> return emptyList()
        }
        return emptyList()
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

    suspend fun replay(
        events: List<ReplayEvent>,
        sessionId: String,
        loader: DbLoader,
    ): List<ReplayChange> {
        val allChanges = mutableListOf<ReplayChange>()
        for (event in events) {
            allChanges += processEvent(event, sessionId, loader)
        }
        return allChanges
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
