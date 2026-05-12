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
            .map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = syncEvents

    override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> {
        return dbProvider.getActive().sessionMessageDao().getRecentWindow(sessionId, limit).map { it.toDomain() }
    }

    override suspend fun getOlderPage(
        sessionId: String,
        beforeTimeCreated: Long,
        beforeId: String,
        limit: Int,
    ): List<SessionMessage> {
        return dbProvider.getActive().sessionMessageDao()
            .getOlderPage(sessionId, beforeTimeCreated, beforeId, limit)
            .map { it.toDomain() }
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
            val payload = syncApiClient.eventsPayload(sessionId, syncState.lastSeq)
            val response = payload.response
            val t1 = System.currentTimeMillis()
            val packageBytes = payload.rawBody.toByteArray(Charsets.UTF_8).size
            if (response.events.isEmpty()) {
                Log.d("SyncRepo", "<< incrementalSync END: no new events (${t1 - t0}ms)")
                val lastSeq = response.maxSeq ?: syncState.lastSeq
                if (lastSeq != syncState.lastSeq) {
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, lastSeq))
                }
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = sessionId,
                    title = "增量同步结束",
                    message = "incremental sync completed applied=0 cost=${t1 - t0}ms totalBytes=$packageBytes",
                    bytes = packageBytes,
                    relatedSeq = lastSeq,
                    traceId = traceId,
                )
                return SessionMessageSyncResult(lastSeq = lastSeq, changes = emptyList())
            }
            Log.d("SyncRepo", "  fetch events: ${response.events.size} events in ${t1 - t0}ms, maxSeq=${response.maxSeq}")
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "增量包返回",
                message = "events response received eventCount=${response.events.size} maxSeq=${response.maxSeq}",
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
            val t2 = System.currentTimeMillis()
            Log.d("SyncRepo", "  replay: ${events.size} events -> ${changes.size} changes in ${t2 - t1}ms")

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
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "Replay结果",
                message = "replay finished eventCount=${events.size} changeCount=${changes.size} coalescedWrites=${coalesced.size}",
                traceId = traceId,
            )

            val appliedChanges = mutableListOf<SessionMessageSyncChange>()
            db.withTransaction {
                for (change in coalesced.values) {
                    when (change) {
                        is ReplayChange.Insert -> {
                            db.sessionMessageDao().upsert(change.entity)
                            appliedChanges += SessionMessageSyncChange.Insert(change.entity.toDomain())
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
                            appliedChanges += SessionMessageSyncChange.Update(updated.toDomain())
                        }
                    }
                }

                val seq = response.maxSeq ?: 0L
                db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
                val t3 = System.currentTimeMillis()
                Log.d("SyncRepo", "<< incrementalSync END: ${events.size} evts -> ${changes.size} chgs -> ${coalesced.size} writes, lastSeq=$seq, db=${t3 - t2}ms, total=${t3 - t0}ms")
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = sessionId,
                    title = "增量同步结束",
                    message = "incremental sync completed applied=${appliedChanges.size} cost=${t3 - t0}ms totalBytes=$packageBytes",
                    bytes = packageBytes,
                    relatedSeq = seq,
                    traceId = traceId,
                )
            }

            return SessionMessageSyncResult(
                lastSeq = response.maxSeq ?: syncState.lastSeq,
                changes = appliedChanges,
            )
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
        val result = incrementalSync(sessionId)
        syncEvents.emit(SessionMessageSyncEvent(sessionId = sessionId, result = result))
        return result
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
