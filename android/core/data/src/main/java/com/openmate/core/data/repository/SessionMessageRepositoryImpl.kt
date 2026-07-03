package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sync.MobileTruncator
import com.openmate.core.data.sync.SessionMessageMapper
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.domain.model.DiffBuilder
import com.openmate.core.domain.model.DiffFile
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncChange
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncApiClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SessionMessageRepositoryImpl @Inject constructor(
    private val syncApiClient: SyncApiClient,
    private val dbProvider: ActiveDatabaseProvider,
    private val logStore: SyncLogStore,
) : SessionMessageRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val syncEvents = MutableSharedFlow<SessionMessageSyncEvent>(extraBufferCapacity = 64)

    private val syncingSessions = ConcurrentHashMap<String, Unit>()

    override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return dbProvider.getActive().sessionMessageDao().observeBySession(sessionId)
            .map { entities ->
                val latest = entities.lastOrNull()
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    message = "观察消息表 observe messages count=${entities.size} last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
                    sessionId = sessionId,
                )
                entities.map { it.toDomain() }
            }
    }

    override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = syncEvents

    override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao().getRecentWindow(sessionId, limit)
        val costMs = System.currentTimeMillis() - startedAt
        val latest = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "读取消息表 load recent window count=${entities.size} limit=$limit cost=${costMs}ms last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun getOlderPage(
        sessionId: String,
        beforeTimeCreated: Long,
        beforeId: String,
        limit: Int,
    ): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao()
            .getOlderPage(sessionId, beforeTimeCreated, beforeId, limit)
        val costMs = System.currentTimeMillis() - startedAt
        val first = entities.firstOrNull()
        val last = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "读取旧消息页 load older page count=${entities.size} before=$beforeId/$beforeTimeCreated limit=$limit cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun getOlderPageByUserTurns(
        sessionId: String,
        beforeTimeCreated: Long,
        beforeId: String,
        userTurns: Int,
    ): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao()
            .getOlderPageByUserTurns(sessionId, beforeTimeCreated, beforeId, userTurns)
        val costMs = System.currentTimeMillis() - startedAt
        val first = entities.firstOrNull()
        val last = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "按用户轮次加载旧消息 userTurns=$userTurns count=${entities.size} cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun findBusyStartTime(sessionId: String): Long? {
        return dbProvider.getActive().sessionMessageDao().findBusyStartTime(sessionId)
    }

    override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult {
        val db = dbProvider.getActive()
        Log.d("SyncRepo", "initSync start: sessionId=$sessionId")

        val seqResponse = syncApiClient.events(sessionId, Long.MAX_VALUE)
        val currentSeq = seqResponse.maxSeq ?: 0L

        val response = syncApiClient.init(sessionId, limit)
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "initSync got ${response.messages.size} messages ids=${response.messages.take(3).map { it.id }.joinToString(",")}")
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "initSync entities ids=${entities.take(3).map { it.id }.joinToString(",")}")
        db.sessionMessageDao().replaceAllForSession(sessionId, entities)

        val maxTimeUpdated = entities.maxOfOrNull { it.timeUpdated } ?: 0L
        db.syncStateDao().upsert(SyncStateEntity(sessionId, currentSeq, maxTimeUpdated))
        Log.d("SyncRepo", "initSync saved cursor seq=$currentSeq maxTimeUpdated=$maxTimeUpdated")

        return SessionMessageSyncResult(
            lastSeq = currentSeq,
            changes = entities.map { entity ->
                SessionMessageSyncChange.Insert(entity.toDomain())
            },
        )
    }

    override suspend fun incrementalSync(sessionId: String) {
        if (syncingSessions.putIfAbsent(sessionId, Unit) != null) {
            Log.d("SyncRepo", "incrementalSync skip: already syncing $sessionId")
            return
        }
        try {
            doIncrementalSync(sessionId)
        } finally {
            syncingSessions.remove(sessionId)
        }
    }

    private suspend fun doIncrementalSync(sessionId: String) {
        val db = dbProvider.getActive()
        db.sessionMessageDao().fixRunningAssistantRoundMark()
        val syncState = db.syncStateDao().get(sessionId) ?: run {
            Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
            return
        }
        val t0 = System.currentTimeMillis()
        val traceId = "inc-${System.nanoTime()}"
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "增量同步开始 afterSeq=${syncState.lastSeq} afterTime=${syncState.lastTimeUpdated} trace=$traceId",
            sessionId = sessionId,
        )

        try {
            var batchChanges = mutableListOf<SessionMessageSyncChange>()
            var hasTodoEvent = false

            var since = syncState.lastTimeUpdated
            var totalMessages = 0
            var msgBatchIndex = 0
            while (true) {
                val response = syncApiClient.messages(sessionId, since, limit = 100)
                msgBatchIndex++
                totalMessages += response.messages.size
                if (response.messages.isEmpty()) break

                db.withTransaction {
                    for (dto in response.messages) {
                        val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
                        val entity = SessionMessageMapper.dtoToEntity(dto.copy(data = truncatedData))
                        val existing = db.sessionMessageDao().getById(entity.id)
                        db.sessionMessageDao().upsert(entity)
                        batchChanges += if (existing != null) {
                            SessionMessageSyncChange.Update(entity.toDomain())
                        } else {
                            SessionMessageSyncChange.Insert(entity.toDomain())
                        }
                        if (!hasTodoEvent && entity.data.contains("todowrite")) {
                            hasTodoEvent = true
                        }
                    }
                }

                since = response.maxTimeUpdated ?: break
                if (!response.hasMore) break
            }

            val maxTimeUpdated = since

            var afterSeq = syncState.lastSeq
            var totalEvents = 0
            var evtBatchIndex = 0
            while (true) {
                val response = syncApiClient.events(sessionId, afterSeq, limit = 100)
                evtBatchIndex++
                totalEvents += response.events.size
                if (response.events.isEmpty()) {
                    val lastSeq = response.maxSeq ?: afterSeq
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, lastSeq, maxTimeUpdated))
                    break
                }

                db.withTransaction {
                    for (event in response.events) {
                        when {
                            event.type.startsWith("message.removed") && !event.type.startsWith("message.part.removed") -> {
                                val session = db.sessionDao().getById(sessionId)
                                val fromId = session?.revertFrom
                                val toId = session?.revertTo
                                if (fromId != null && toId != null) {
                                    val rangeMessages = db.sessionMessageDao().getInRange(sessionId, fromId, toId)
                                    db.sessionMessageDao().deleteRange(sessionId, fromId, toId)
                                    for (msg in rangeMessages) {
                                        batchChanges += SessionMessageSyncChange.Remove(msg.id)
                                    }
                                    logStore.log(
                                        level = SyncLogLevel.Info,
                                        category = SyncLogCategory.Sync,
                                        message = "revert范围删除 from=$fromId to=$toId count=${rangeMessages.size} seq=${event.seq} trace=$traceId",
                                        sessionId = sessionId,
                                    )
                                }
                                db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
                            }
                            event.type.startsWith("session.updated") -> {
                                val aggId = event.aggregateId.ifBlank { sessionId }
                                val info = event.data["info"]?.jsonObject
                                val revertJson = info?.get("revert")
                                val hasRevertKey = info?.contains("revert") == true
                                if (revertJson is JsonObject) {
                                    val revertMsgID = revertJson["messageID"]?.jsonPrimitive?.content
                                    val revertPartID = revertJson["partID"]?.jsonPrimitive?.content
                                    var fromId: String? = null
                                    var toId: String? = null
                                    if (revertMsgID != null) {
                                        val storedTs = extractStoredTs(revertMsgID)
                                        val nowK = System.currentTimeMillis() / MOD_2_36
                                        for (k in nowK downTo maxOf(0L, nowK - 1)) {
                                            val firstId = db.sessionMessageDao().getFirstIdAfterTimeCreated(sessionId, storedTs + k * MOD_2_36)
                                            if (firstId != null) {
                                                fromId = firstId
                                                toId = db.sessionMessageDao().getMaxIdGte(sessionId, firstId)
                                                break
                                            }
                                        }
                                    }
                                    val rows = db.sessionDao().updateRevertFields(aggId, revertMsgID, revertPartID, fromId, toId)
                                    logStore.log(
                                        level = if (rows == 0) SyncLogLevel.Error else SyncLogLevel.Info,
                                        category = SyncLogCategory.Sync,
                                        message = "revert写入结果 aggId=$aggId msgID=$revertMsgID from=$fromId to=$toId rows=$rows seq=${event.seq} trace=$traceId",
                                        sessionId = sessionId,
                                    )
                                } else if (hasRevertKey && revertJson is JsonNull) {
                                    val rows = db.sessionDao().updateRevertFields(aggId, null, null, null, null)
                                    logStore.log(
                                        level = if (rows == 0) SyncLogLevel.Error else SyncLogLevel.Info,
                                        category = SyncLogCategory.Sync,
                                        message = "revert清除结果 aggId=$aggId rows=$rows seq=${event.seq} trace=$traceId",
                                        sessionId = sessionId,
                                    )
                                } else if (!hasRevertKey) {
                                    val existing = db.sessionDao().getById(aggId)
                                    if (existing?.revertMessageID != null) {
                                        val rows = db.sessionDao().updateRevertFields(aggId, null, null, null, null)
                                        logStore.log(
                                            level = if (rows == 0) SyncLogLevel.Error else SyncLogLevel.Info,
                                            category = SyncLogCategory.Sync,
                                            message = "revert隐式清除(无key) aggId=$aggId rows=$rows seq=${event.seq} trace=$traceId",
                                            sessionId = sessionId,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val batchMaxSeq = response.events.maxOfOrNull { it.seq }
                    val newSeq = batchMaxSeq?.let { maxOf(afterSeq, it) } ?: afterSeq
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq, maxTimeUpdated))
                    afterSeq = newSeq
                }
            }

            if (batchChanges.isNotEmpty()) {
                val finalState = db.syncStateDao().get(sessionId)
                syncEvents.tryEmit(
                    SessionMessageSyncEvent(
                        sessionId = sessionId,
                        result = SessionMessageSyncResult(
                            lastSeq = finalState?.lastSeq ?: syncState.lastSeq,
                            changes = batchChanges,
                            hasTodoEvent = hasTodoEvent,
                        ),
                    )
                )
            }

            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                message = "增量同步完成 msgs=$totalMessages evts=$totalEvents cost=${System.currentTimeMillis() - t0}ms trace=$traceId",
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            logStore.log(
                level = SyncLogLevel.Error,
                category = SyncLogCategory.Sync,
                message = "增量同步失败 afterSeq=${syncState.lastSeq} error=${e.javaClass.simpleName}: ${e.message} cost=${System.currentTimeMillis() - t0}ms trace=$traceId",
                sessionId = sessionId,
            )
            throw e
        }
    }

    override suspend fun incrementalSyncAndNotify(sessionId: String) {
        incrementalSync(sessionId)
    }

    override suspend fun fetchFullMessage(sessionId: String, messageId: String) {
        val db = dbProvider.getActive()
        val response = syncApiClient.full(sessionId, messageId)
        db.sessionMessageFullContentDao().upsert(
            SessionMessageFullContentEntity(
                messageId = response.id,
                content = response.data.toString(),
                fetchedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun fetchDiffFiles(sessionId: String, messageId: String, toolName: String, targetFilePath: String?): List<DiffFile> {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles IN sessionId=$sessionId messageId=$messageId toolName=$toolName targetFilePath=$targetFilePath")
        val response = syncApiClient.full(sessionId, messageId)
        val data = response.data
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles RESP id=${response.id} type=${response.type} dataKeys=${data.keys.joinToString(",")}")
        val contentArray = data["content"]?.jsonArray
        if (contentArray == null) {
            logStore.log(SyncLogLevel.Error, SyncLogCategory.Connection, "fetchDiffFiles: content NOT array, raw=${data["content"].toString().take(200)}")
            return emptyList()
        }
        for (item in contentArray) {
            val partData = item.jsonObject
            val type = partData["type"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type != "tool") continue
            val state = partData["state"]?.jsonObject ?: continue
            val tool = state["tool"]?.jsonPrimitive?.contentOrNull
                ?: partData["name"]?.jsonPrimitive?.contentOrNull
                ?: continue
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles: tool=$tool want=$toolName stateKeys=${state.keys.joinToString(",")}")
            if (tool != toolName) continue

            val structured = state["structured"]?.jsonObject

            if (toolName == "apply_patch") {
                val structuredFiles = structured?.get("files")?.jsonArray
                if (structuredFiles != null) {
                    for (sf in structuredFiles) {
                        val sfObj = sf.jsonObject
                        val fp = sfObj["filePath"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (targetFilePath != null && !fp.replace('\\', '/').endsWith("/${targetFilePath.replace('\\', '/')}") && fp != targetFilePath) continue
                        val patchText = sfObj["patch"]?.jsonPrimitive?.contentOrNull ?: continue
                        val parsed = DiffBuilder.fromUnifiedDiff(patchText)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
                val diffText = structured?.get("diff")?.jsonPrimitive?.contentOrNull
                if (!diffText.isNullOrBlank()) {
                    val parsed = DiffBuilder.fromUnifiedDiff(diffText)
                    return if (targetFilePath != null) parsed.filter {
                        it.filePath.replace('\\', '/').endsWith("/${targetFilePath.replace('\\', '/')}") || it.filePath == targetFilePath
                    } else parsed
                }
                val input = state["input"]?.jsonObject ?: return emptyList()
                val patchText = input["patchText"]?.jsonPrimitive?.contentOrNull
                    ?: input["patch_text"]?.jsonPrimitive?.contentOrNull
                    ?: return emptyList()
                return DiffBuilder.fromApplyPatchFallback(patchText)
            }

            val diffText = structured?.get("diff")?.jsonPrimitive?.contentOrNull
                ?: structured?.get("filediff")?.jsonObject?.get("patch")?.jsonPrimitive?.contentOrNull

            val files = if (!diffText.isNullOrBlank()) {
                DiffBuilder.fromUnifiedDiff(diffText)
            } else {
                val input = state["input"]?.jsonObject ?: return emptyList()
                when (toolName) {
                    "edit" -> {
                        val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
                            ?: input["file_path"]?.jsonPrimitive?.contentOrNull
                            ?: return emptyList()
                        val oldString = input["oldString"]?.jsonPrimitive?.contentOrNull
                            ?: input["old_string"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val newString = input["newString"]?.jsonPrimitive?.contentOrNull
                            ?: input["new_string"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val diffFile = DiffBuilder.fromEditFallback(filePath, oldString, newString) ?: return emptyList()
                        listOf(diffFile)
                    }
                    "apply_patch" -> {
                        val patchText = input["patchText"]?.jsonPrimitive?.contentOrNull
                            ?: input["patch_text"]?.jsonPrimitive?.contentOrNull
                            ?: return emptyList()
                        DiffBuilder.fromApplyPatchFallback(patchText)
                    }
                    else -> emptyList()
                }
            }
            return files
        }
        return emptyList()
    }

    override suspend fun getLastSeq(sessionId: String): Long? {
        return dbProvider.getActive().syncStateDao().get(sessionId)?.lastSeq
    }

    override suspend fun resyncFrom(sessionId: String, sinceTimeUpdated: Long) {
        val db = dbProvider.getActive()
        db.sessionMessageDao().deleteBySessionBeforeTimeUpdated(sessionId, sinceTimeUpdated)
        db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
        db.syncStateDao().updateLastTimeUpdated(sessionId, sinceTimeUpdated)
        incrementalSync(sessionId)
    }

    override suspend fun getMinTimeCreated(sessionId: String): Long? {
        return dbProvider.getActive().sessionMessageDao().getMinTimeCreated(sessionId)
    }

    override suspend fun countBySessionAfterTimeCreated(sessionId: String, since: Long): Int {
        return dbProvider.getActive().sessionMessageDao().countBySessionAfterTimeCreated(sessionId, since)
    }

    override suspend fun countBySession(sessionId: String): Int {
        return dbProvider.getActive().sessionMessageDao().countBySession(sessionId)
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String) {
        dbProvider.getActive().sessionMessageDao().delete(messageId)
    }
}

private const val MOD_2_36 = 68719476736L

private fun extractStoredTs(messageId: String): Long {
    val hex = messageId.split("_")[1].take(12)
    return hex.toLong(16) / 4096L
}

private fun SessionMessageEntity.toDomain() = SessionMessage(
    id = id,
    sessionId = sessionId,
    type = type,
    data = data,
    timeCreated = timeCreated,
    timeUpdated = timeUpdated,
    completedAt = completedAt,
    roundMark = roundMark,
)
