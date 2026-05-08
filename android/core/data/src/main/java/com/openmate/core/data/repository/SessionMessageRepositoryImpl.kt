package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sync.EventReplayer
import com.openmate.core.data.sync.MobileTruncator
import com.openmate.core.data.sync.ReplayChange
import com.openmate.core.data.sync.ReplayEvent
import com.openmate.core.data.sync.SessionMessageMapper
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject
import androidx.room.withTransaction
import javax.inject.Inject

class SessionMessageRepositoryImpl @Inject constructor(
    private val syncApiClient: SyncApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : SessionMessageRepository {

    override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return dbProvider.getActive().sessionMessageDao().observeBySession(sessionId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun initSync(sessionId: String, limit: Int) {
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
    }

    override suspend fun incrementalSync(sessionId: String) {
        val db = dbProvider.getActive()
        val syncState = db.syncStateDao().get(sessionId) ?: run {
            Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
            return
        }
        val t0 = System.currentTimeMillis()
        Log.d("SyncRepo", ">> incrementalSync START: sessionId=$sessionId afterSeq=${syncState.lastSeq}")

        val response = syncApiClient.events(sessionId, syncState.lastSeq)
        val t1 = System.currentTimeMillis()
        if (response.events.isEmpty()) {
            Log.d("SyncRepo", "<< incrementalSync END: no new events (${t1 - t0}ms)")
            return
        }
        Log.d("SyncRepo", "  fetch events: ${response.events.size} events in ${t1 - t0}ms, maxSeq=${response.maxSeq}")

        val loader = EventReplayer.DbLoader { action ->
            when (action) {
                is EventReplayer.DbLoader.Action.LoadById ->
                    db.sessionMessageDao().getById(action.id)
                is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant -> {
                    db.sessionMessageDao().getLatestAssistant(action.sessionId)?.takeIf {
                        !it.data.contains("\"completed\"")
                    }
                }
            }
        }

        val replayer = EventReplayer()
        val events = response.events.map { e -> ReplayEvent(e.id, e.type, e.data) }
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
                coalesced[key] = ReplayChange.Insert(prev.entity.copy(
                    data = change.data.toString(),
                    timeUpdated = change.timeUpdated,
                ))
            } else {
                coalesced[key] = change
            }
        }

        db.withTransaction {
            for (change in coalesced.values) {
                when (change) {
                    is ReplayChange.Insert -> {
                        db.sessionMessageDao().upsert(change.entity)
                    }
                    is ReplayChange.Update -> {
                        val existing = db.sessionMessageDao().getById(change.id) ?: continue
                        db.sessionMessageDao().upsert(SessionMessageEntity(
                            id = change.id,
                            sessionId = existing.sessionId,
                            type = existing.type,
                            data = change.data.toString(),
                            timeCreated = existing.timeCreated,
                            timeUpdated = change.timeUpdated,
                        ))
                    }
                }
            }

            val seq = response.maxSeq ?: 0L
            db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
            val t3 = System.currentTimeMillis()
            Log.d("SyncRepo", "<< incrementalSync END: ${events.size} evts -> ${changes.size} chgs -> ${coalesced.size} writes, lastSeq=$seq, db=${t3 - t2}ms, total=${t3 - t0}ms")
        }
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
)