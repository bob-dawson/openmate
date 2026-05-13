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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import androidx.room.withTransaction
import javax.inject.Inject

class SessionMessageRepositoryImpl @Inject constructor(
    private val syncApiClient: SyncApiClient,
    private val dbProvider: ActiveDatabaseProvider,
    private val logStore: SyncLogStore,
) : SessionMessageRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val syncEvents = MutableSharedFlow<SessionMessageSyncEvent>(extraBufferCapacity = 64)

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

    override suspend fun incrementalSync(sessionId: String): SessionMessageSyncResult {
        val db = dbProvider.getActive()
        db.sessionMessageDao().fixRunningAssistantRoundMark()
        val syncState = db.syncStateDao().get(sessionId) ?: run {
            Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
            return SessionMessageSyncResult(lastSeq = 0L, changes = emptyList())
        }
        val t0 = System.currentTimeMillis()
        val traceId = "inc-${System.nanoTime()}"
        Log.d("SyncRepo", ">> incrementalSync START: sessionId=$sessionId afterSeq=${syncState.lastSeq}")
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
            val allAppliedChanges = mutableListOf<SessionMessageSyncChange>()
            var totalEvents = 0
            var totalBytes = 0L
            var batchIndex = 0

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
                    Log.d("SyncRepo", "<< incrementalSync END: no more events (batch=$batchIndex totalEvts=$totalEvents totalBytes=$totalBytes ${System.currentTimeMillis() - t0}ms)")
                    logStore.log(
                        level = SyncLogLevel.Info,
                        category = SyncLogCategory.Sync,
                        sessionId = sessionId,
                        title = "增量同步结束",
                        message = "incremental sync completed batches=$batchIndex applied=${allAppliedChanges.size} totalEvents=$totalEvents cost=${System.currentTimeMillis() - t0}ms totalBytes=$totalBytes",
                        bytes = totalBytes.toInt(),
                        relatedSeq = lastSeq,
                        traceId = traceId,
                    )
                    return SessionMessageSyncResult(lastSeq = lastSeq, changes = allAppliedChanges)
                }

                val eventCount = response.events.size
                totalEvents += eventCount
                Log.d("SyncRepo", "  batch $batchIndex: $eventCount events, maxSeq=${response.maxSeq}")
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = sessionId,
                    title = "增量包返回",
                    message = "batch=$batchIndex eventCount=$eventCount maxSeq=${response.maxSeq}",
                    bytes = packageBytes,
                    traceId = traceId,
                )

                val loader = EventReplayer.DbLoader { action ->
                    when (action) {
                        is EventReplayer.DbLoader.Action.LoadById ->
                            db.sessionMessageDao().getById(action.id)
                        is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant ->
                            db.sessionMessageDao().getLatestIncompleteAssistant(action.sessionId)
                        is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction ->
                            db.sessionMessageDao().getLatestIncompleteCompaction(action.sessionId)
                        is EventReplayer.DbLoader.Action.LoadAssistantByToolCallId ->
                            db.sessionMessageDao().getAssistantByToolCallId(action.sessionId, action.callID)
                    }
                }

                val replayer = EventReplayer()
                val events = response.events.map { e -> ReplayEvent(e.id, e.type, e.data) }
                response.events.forEachIndexed { index, event ->
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
                }
                val changes = replayer.replay(events, sessionId, loader)
                Log.d("SyncRepo", "  replay: ${events.size} events -> ${changes.size} changes")

                val coalesced = mutableMapOf<String, ReplayChange>()
                for (change in changes) {
                    val key = when (change) {
                        is ReplayChange.Insert -> change.entity.id
                        is ReplayChange.Update -> change.id
                    }
                    val prev = coalesced[key]
                    if (prev is ReplayChange.Insert && change is ReplayChange.Update) {
                        val dataCompletedAt = change.data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                        coalesced[key] = ReplayChange.Insert(prev.entity.copy(
                            data = change.data.toString(),
                            timeUpdated = change.timeUpdated,
                            completedAt = dataCompletedAt ?: change.completedAt ?: prev.entity.completedAt,
                            roundMark = change.roundMark ?: prev.entity.roundMark,
                        ))
                    } else {
                        coalesced[key] = change
                    }
                }

                db.withTransaction {
                    val batchChanges = mutableListOf<SessionMessageSyncChange>()
                    for (change in coalesced.values) {
                        when (change) {
                            is ReplayChange.Insert -> {
                                db.sessionMessageDao().upsert(change.entity)
                                batchChanges += SessionMessageSyncChange.Insert(change.entity.toDomain())
                                allAppliedChanges += SessionMessageSyncChange.Insert(change.entity.toDomain())
                            }
                            is ReplayChange.Update -> {
                                val existing = db.sessionMessageDao().getById(change.id) ?: continue
                                val dataCompletedAt = change.data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                                val updated = SessionMessageEntity(
                                    id = change.id,
                                    sessionId = existing.sessionId,
                                    type = existing.type,
                                    data = change.data.toString(),
                                    timeCreated = existing.timeCreated,
                                    timeUpdated = change.timeUpdated,
                                    completedAt = dataCompletedAt ?: change.completedAt ?: existing.completedAt,
                                    roundMark = change.roundMark ?: existing.roundMark,
                                )
                                db.sessionMessageDao().upsert(updated)
                                batchChanges += SessionMessageSyncChange.Update(updated.toDomain())
                                allAppliedChanges += SessionMessageSyncChange.Update(updated.toDomain())
                            }
                        }
                    }

                    val batchMaxSeq = response.events.maxOfOrNull { it.seq }
                    val newSeq = batchMaxSeq?.let { maxOf(afterSeq, it) } ?: afterSeq
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq))
                    afterSeq = newSeq

                    if (batchChanges.isNotEmpty()) {
                        syncEvents.emit(SessionMessageSyncEvent(
                            sessionId = sessionId,
                            result = SessionMessageSyncResult(lastSeq = newSeq, changes = batchChanges),
                        ))
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

    override suspend fun incrementalSyncAndNotify(sessionId: String): SessionMessageSyncResult {
        return incrementalSync(sessionId)
    }

    override suspend fun fetchFullMessage(sessionId: String, messageId: String) {
        val db = dbProvider.getActive()
        val response = syncApiClient.full(sessionId, messageId)
        db.sessionMessageFullContentDao().upsert(SessionMessageFullContentEntity(
            messageId = response.id,
            content = response.data.toString(),
            fetchedAt = System.currentTimeMillis(),
        ))
    }

    override suspend fun getLastSeq(sessionId: String): Long? {
        return dbProvider.getActive().syncStateDao().get(sessionId)?.lastSeq
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
