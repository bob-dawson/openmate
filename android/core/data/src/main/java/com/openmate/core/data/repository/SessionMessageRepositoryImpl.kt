package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sync.EventReplayer
import com.openmate.core.data.sync.MobileTruncator
import com.openmate.core.data.sync.ReplayChange
import com.openmate.core.data.sync.ReplayEvent
import com.openmate.core.data.sync.SessionMessageMapper
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncChange
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncApiClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                    sessionId = sessionId,
                    title = "观察消息表",
                    message = "observe messages count=${entities.size} last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
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
            sessionId = sessionId,
            title = "读取消息表",
            message = "load recent window count=${entities.size} limit=$limit cost=${costMs}ms last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
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
            sessionId = sessionId,
            title = "读取旧消息页",
            message = "load older page count=${entities.size} before=$beforeId/$beforeTimeCreated limit=$limit cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
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
            sessionId = sessionId,
            title = "按用户轮次加载旧消息",
            message = "userTurns=$userTurns count=${entities.size} cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
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
        db.syncStateDao().upsert(SyncStateEntity(sessionId, currentSeq))
        Log.d("SyncRepo", "initSync saved cursor seq=$currentSeq")

        val response = syncApiClient.init(sessionId, limit)
        Log.d("SyncRepo", "initSync got ${response.messages.size} messages")
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        db.sessionMessageDao().replaceAllForSession(sessionId, entities)

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
        Log.d(
            "SyncRepo",
            ">> incrementalSync START: sessionId=$sessionId afterSeq=${syncState.lastSeq}"
        )
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            sessionId = sessionId,
            title = "增量同步开始",
            message = "incremental sync begin afterSeq=${syncState.lastSeq}",
            relatedSeq = syncState.lastSeq,
            traceId = traceId,
        )

        try {
            var afterSeq = syncState.lastSeq
            var totalEvents = 0
            var totalBytes = 0L
            var batchIndex = 0

            val loader = EventReplayer.DbLoader { action ->
                when (action) {
                    is EventReplayer.DbLoader.Action.LoadById ->
                        db.sessionMessageDao().getById(action.id)

                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant ->
                        db.sessionMessageDao().getLatestIncompleteAssistant(action.sessionId)

                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction ->
                        db.sessionMessageDao().getLatestIncompleteCompaction(action.sessionId)

                    is EventReplayer.DbLoader.Action.LoadAssistantByToolCallId ->
                        db.sessionMessageDao()
                            .getAssistantByToolCallId(action.sessionId, action.callID)
                }
            }
            val replayer = EventReplayer()

            while (true) {
                batchIndex++
                val payload = syncApiClient.eventsPayload(sessionId, afterSeq)
                val response = payload.response
                val packageBytes = payload.rawBody.toByteArray(Charsets.UTF_8).size
                totalBytes += packageBytes

                if (response.events.isEmpty()) {
                    val lastSeq = response.maxSeq ?: afterSeq
                    if (lastSeq != afterSeq) {
                        db.syncStateDao().upsert(SyncStateEntity(sessionId, lastSeq))
                    }
                    Log.d(
                        "SyncRepo",
                        "<< incrementalSync END: no more events (batch=$batchIndex totalEvts=$totalEvents totalBytes=$totalBytes ${System.currentTimeMillis() - t0}ms)"
                    )
                    logStore.log(
                        level = SyncLogLevel.Info,
                        category = SyncLogCategory.Sync,
                        sessionId = sessionId,
                        title = "增量同步结束",
                        message = "incremental sync completed batches=$batchIndex totalEvents=$totalEvents cost=${System.currentTimeMillis() - t0}ms totalBytes=$totalBytes",
                        bytes = totalBytes.toInt(),
                        relatedSeq = lastSeq,
                        traceId = traceId,
                    )
                    return
                }

                val eventCount = response.events.size
                totalEvents += eventCount
                Log.d(
                    "SyncRepo",
                    "  batch $batchIndex: $eventCount events, maxSeq=${response.maxSeq}"
                )
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = sessionId,
                    title = "增量包返回",
                    message = "batch=$batchIndex eventCount=$eventCount maxSeq=${response.maxSeq}",
                    bytes = packageBytes,
                    traceId = traceId,
                )

                val revertIdMap = mutableMapOf<String, String?>()
                for (event in response.events) {
                    if (!event.type.startsWith("session.updated")) continue
                    val info = event.data["info"]?.jsonObject
                    val revertJson = info?.get("revert")
                    if (revertJson is JsonObject) {
                        val revertMsgID =
                            revertJson["messageID"]?.jsonPrimitive?.content ?: continue
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionId,
                            title = "revert预解析",
                            message = "seq=${event.seq} msgID=$revertMsgID",
                            relatedSeq = event.seq,
                            traceId = traceId,
                        )
                        val evtId =
                            runCatching { syncApiClient.resolveEvtID(sessionId, revertMsgID) }
                                .onFailure {
                                    Log.w("SyncRepo", "resolveEvtID failed for $revertMsgID", it)
                                    logStore.log(
                                        level = SyncLogLevel.Error,
                                        category = SyncLogCategory.Sync,
                                        sessionId = sessionId,
                                        title = "revert预解析失败",
                                        message = "seq=${event.seq} msgID=$revertMsgID error=${it.javaClass.simpleName}: ${it.message}",
                                        relatedSeq = event.seq,
                                        traceId = traceId,
                                    )
                                }
                                .getOrNull()
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionId,
                            title = "revert预解析结果",
                            message = "seq=${event.seq} msgID=$revertMsgID evtId=$evtId",
                            relatedSeq = event.seq,
                            traceId = traceId,
                        )
                        revertIdMap[revertMsgID] = evtId
                    }
                }

                db.withTransaction {
                    var pendingId: String? = null
                    var pendingIsInsert: Boolean = false
                    var pendingEntity: SessionMessageEntity? = null
                    val batchChanges = mutableListOf<SessionMessageSyncChange>()

                    suspend fun flushPending() {
                        val entity = pendingEntity ?: return
                        pendingEntity = null
                        pendingId = null
                        db.sessionMessageDao().upsert(entity)
                        if (pendingIsInsert) {
                            batchChanges += SessionMessageSyncChange.Insert(entity.toDomain())
                        } else {
                            batchChanges += SessionMessageSyncChange.Update(entity.toDomain())
                        }
                    }

                    for ((index, event) in response.events.withIndex()) {
                        val rawEventBody = payload.rawEventBodies.getOrNull(index)
                        val bytesValue = rawEventBody?.toByteArray(Charsets.UTF_8)?.size
                            ?: json.encodeToString(event).toByteArray(Charsets.UTF_8).size
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionId,
                            title = "增量消息处理",
                            message = "event type=${event.type} aggregateId=${event.aggregateId}",
                            bytes = bytesValue,
                            relatedSeq = event.seq,
                            traceId = traceId,
                        )

                        if (event.type.startsWith("message.removed") || event.type.startsWith("message.part.removed")) {
                            val session = db.sessionDao().getById(sessionId)
                            val fromId = session?.revertFrom
                            val toId = session?.revertTo
                            if (fromId != null && toId != null) {
                                val toDelete = db.sessionMessageDao().getInRange(sessionId, fromId, toId)
                                for (msg in toDelete) {
                                    batchChanges += SessionMessageSyncChange.Remove(msg.id)
                                }
                                db.sessionMessageDao().deleteRange(sessionId, fromId, toId)
                                logStore.log(
                                    level = SyncLogLevel.Info,
                                    category = SyncLogCategory.Sync,
                                    sessionId = sessionId,
                                    title = "revert范围删除",
                                    message = "seq=${event.seq} aggId=$sessionId fromId=$fromId toId=$toId count=${toDelete.size}",
                                    relatedSeq = event.seq,
                                    traceId = traceId,
                                )
                            }
                            db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
                        }

                        val replayEvent = ReplayEvent(event.id, event.type.replace(Regex("\\.\\d+$"), ""), event.data)
                        val changes = replayer.processEvent(replayEvent, sessionId, loader)
                        for (change in changes) {
                            val changeId = when (change) {
                                is ReplayChange.Insert -> change.entity.id
                                is ReplayChange.Update -> change.id
                                is ReplayChange.Delete -> change.id
                            }
                            if (pendingId != null && pendingId != changeId) {
                                flushPending()
                            }
                            when (change) {
                                is ReplayChange.Insert -> {
                                    pendingId = changeId
                                    pendingIsInsert = true
                                    pendingEntity = change.entity
                                }

                                is ReplayChange.Update -> {
                                    val dataCompletedAt =
                                        change.data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                                    if (pendingId == changeId && pendingIsInsert) {
                                        pendingEntity = pendingEntity!!.copy(
                                            data = change.data.toString(),
                                            timeUpdated = change.timeUpdated,
                                            completedAt = dataCompletedAt ?: change.completedAt
                                            ?: pendingEntity!!.completedAt,
                                            roundMark = change.roundMark
                                                ?: pendingEntity!!.roundMark,
                                        )
                                    } else if (pendingId == changeId && !pendingIsInsert) {
                                        pendingEntity = SessionMessageEntity(
                                            id = pendingEntity!!.id,
                                            sessionId = pendingEntity!!.sessionId,
                                            type = pendingEntity!!.type,
                                            data = change.data.toString(),
                                            timeCreated = pendingEntity!!.timeCreated,
                                            timeUpdated = change.timeUpdated,
                                            completedAt = dataCompletedAt ?: change.completedAt
                                            ?: pendingEntity!!.completedAt,
                                            roundMark = change.roundMark
                                                ?: pendingEntity!!.roundMark,
                                        )
                                    } else {
                                        val existing =
                                            db.sessionMessageDao().getById(change.id) ?: continue
                                        pendingId = changeId
                                        pendingIsInsert = false
                                        pendingEntity = SessionMessageEntity(
                                            id = change.id,
                                            sessionId = existing.sessionId,
                                            type = existing.type,
                                            data = change.data.toString(),
                                            timeCreated = existing.timeCreated,
                                            timeUpdated = change.timeUpdated,
                                            completedAt = dataCompletedAt ?: change.completedAt
                                            ?: existing.completedAt,
                                            roundMark = change.roundMark ?: existing.roundMark,
                                        )
                                    }
                                }

                                is ReplayChange.Delete -> {
                                    if (pendingId == changeId) {
                                        pendingId = null
                                        pendingEntity = null
                                    }
                                    db.sessionMessageDao().delete(change.id)
                                    batchChanges += SessionMessageSyncChange.Remove(change.id)
                                }
                            }
                        }

                        if (event.type.startsWith("session.updated")) {
                            val aggId = event.aggregateId.ifBlank { sessionId }
                            flushPending()
                            val info = event.data["info"]?.jsonObject
                            val revertJson = info?.get("revert")
                            val hasRevertKey = info?.contains("revert") == true
                            if (revertJson is JsonObject) {
                                val revertMsgID = revertJson["messageID"]?.jsonPrimitive?.content
                                val revertPartID = revertJson["partID"]?.jsonPrimitive?.content
                                val fromId = revertMsgID?.let { revertIdMap[it] }
                                val toId = if (fromId != null) db.sessionMessageDao()
                                    .getMaxIdGte(aggId, fromId) else null
                                val rows = db.sessionDao().updateRevertFields(aggId, revertMsgID, revertPartID, fromId, toId)
                                logStore.log(
                                    level = if (rows == 0) SyncLogLevel.Error else SyncLogLevel.Info,
                                    category = SyncLogCategory.Sync,
                                    sessionId = sessionId,
                                    title = "revert写入结果",
                                    message = "seq=${event.seq} aggId=$aggId msgID=$revertMsgID fromId=$fromId toId=$toId rows=$rows",
                                    relatedSeq = event.seq,
                                    traceId = traceId,
                                )
                            } else if (hasRevertKey && revertJson is JsonNull) {
                                val rows = db.sessionDao().updateRevertFields(aggId, null, null, null, null)
                                logStore.log(
                                    level = if (rows == 0) SyncLogLevel.Error else SyncLogLevel.Info,
                                    category = SyncLogCategory.Sync,
                                    sessionId = sessionId,
                                    title = "revert清除结果",
                                    message = "seq=${event.seq} aggId=$aggId rows=$rows",
                                    relatedSeq = event.seq,
                                    traceId = traceId,
                                )
                            }
                        }
                    }

                    flushPending()

                    val batchMaxSeq = response.events.maxOfOrNull { it.seq }
                    val newSeq = batchMaxSeq?.let { maxOf(afterSeq, it) } ?: afterSeq
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq))
                    afterSeq = newSeq

                    if (batchChanges.isNotEmpty()) {
                        syncEvents.tryEmit(
                            SessionMessageSyncEvent(
                                sessionId = sessionId,
                                result = SessionMessageSyncResult(
                                    lastSeq = newSeq,
                                    changes = batchChanges
                                ),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val totalMs = System.currentTimeMillis() - t0
            logStore.log(
                level = SyncLogLevel.Error,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "增量同步失败",
                message = "incremental sync failed afterSeq=${syncState.lastSeq} error=${e.javaClass.simpleName}: ${e.message} cost=${totalMs}ms",
                traceId = traceId,
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

    override suspend fun getLastSeq(sessionId: String): Long? {
        return dbProvider.getActive().syncStateDao().get(sessionId)?.lastSeq
    }

    override suspend fun rollbackSeq(sessionId: String, count: Long) {
        val db = dbProvider.getActive()
        val current = db.syncStateDao().get(sessionId)?.lastSeq ?: return
        val newSeq = maxOf(0L, current - count)
        db.sessionMessageDao().deleteBySession(sessionId)
        db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
        db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq))
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String) {
        dbProvider.getActive().sessionMessageDao().delete(messageId)
    }
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
