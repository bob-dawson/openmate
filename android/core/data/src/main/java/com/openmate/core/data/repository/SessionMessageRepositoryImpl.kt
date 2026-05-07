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
        val response = syncApiClient.init(sessionId, limit)
        Log.d("SyncRepo", "initSync got ${response.messages.size} messages, maxSeq=${response.maxSeq}")
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        db.sessionMessageDao().replaceAllForSession(sessionId, entities)
        response.maxSeq?.let { seq ->
            db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
            Log.d("SyncRepo", "initSync saved maxSeq=$seq to sync_state")
        }
    }

    override suspend fun incrementalSync(sessionId: String) {
        val db = dbProvider.getActive()
        val syncState = db.syncStateDao().get(sessionId) ?: run {
            Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
            return
        }
        Log.d("SyncRepo", "incrementalSync: sessionId=$sessionId afterSeq=${syncState.lastSeq}")
        val response = syncApiClient.events(sessionId, syncState.lastSeq)
        if (response.events.isEmpty()) {
            Log.d("SyncRepo", "incrementalSync: no new events")
            return
        }
        Log.d("SyncRepo", "incrementalSync: got ${response.events.size} events, maxSeq=${response.maxSeq}")

        val existingMessages = db.sessionMessageDao().getAllBySession(sessionId)
        val existingIds = existingMessages.map { it.id }.toSet()

        val replayer = EventReplayer()
        val events = response.events.map { e -> ReplayEvent(e.id, e.type, e.data) }
        val changes = replayer.replay(events, sessionId, existingMessages)

        for (change in changes) {
            when (change) {
                is ReplayChange.Insert -> {
                    if (change.entity.id in existingIds) continue
                    val truncated = MobileTruncator.truncate(change.entity.type,
                        kotlinx.serialization.json.Json.parseToJsonElement(change.entity.data).jsonObject)
                    db.sessionMessageDao().upsert(change.entity.copy(data = truncated.toString()))
                }
                is ReplayChange.Update -> {
                    val existing = db.sessionMessageDao().getById(change.id) ?: continue
                    val truncated = MobileTruncator.truncate(existing.type, change.data)
                    db.sessionMessageDao().upsert(SessionMessageEntity(
                        id = change.id,
                        sessionId = sessionId,
                        type = existing.type,
                        data = truncated.toString(),
                        timeCreated = existing.timeCreated,
                        timeUpdated = change.timeUpdated,
                    ))
                }
            }
        }

        response.maxSeq?.let { seq ->
            db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
            Log.d("SyncRepo", "incrementalSync updated lastSeq=$seq")
        }
    }

    override suspend fun fetchFullMessage(sessionId: String, messageId: String) {
        val db = dbProvider.getActive()
        val response = syncApiClient.fullMessage(sessionId, messageId)
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