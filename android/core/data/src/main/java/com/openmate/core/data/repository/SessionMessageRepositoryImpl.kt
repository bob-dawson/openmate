package com.openmate.core.data.repository

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
        val response = syncApiClient.init(sessionId, limit)
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        db.sessionMessageDao().replaceAllForSession(sessionId, entities)
        response.maxSeq?.let { seq ->
            db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
        }
    }

    override suspend fun incrementalSync(sessionId: String) {
        val db = dbProvider.getActive()
        val state = db.syncStateDao().get(sessionId) ?: return
        val response = syncApiClient.events(sessionId, state.lastSeq)
        if (response.events.isEmpty()) return

        val replayer = EventReplayer()
        val events = response.events.map { ReplayEvent(it.id, it.type, it.data) }
        val changes = replayer.replay(events, sessionId)

        for (change in changes) {
            when (change) {
                is ReplayChange.Insert -> {
                    db.sessionMessageDao().upsert(change.entity)
                }
                is ReplayChange.Update -> {
                    val existing = db.sessionMessageDao().getById(change.id)
                    val timeCreated = existing?.timeCreated ?: 0L
                    val truncatedData = MobileTruncator.truncate(change.type, change.data)
                    db.sessionMessageDao().upsert(SessionMessageEntity(
                        id = change.id,
                        sessionId = change.sessionId,
                        type = change.type,
                        data = truncatedData.toString(),
                        timeCreated = timeCreated,
                        timeUpdated = change.timeUpdated,
                    ))
                }
            }
        }

        response.maxSeq?.let { seq ->
            db.syncStateDao().upsert(SyncStateEntity(sessionId, seq))
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
