package com.openmate.core.data.repository

import com.openmate.core.data.sse.SessionRetryStateStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncApiClient
import com.openmate.core.network.dto.SessionStatusDto
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val syncApiClient: SyncApiClient,
    private val dbProvider: ActiveDatabaseProvider,
    private val retryStateStore: SessionRetryStateStore,
) : SessionRepository {

    override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> {
        val dtos = api.listSessions(directory, limit, start)
        val dao = dbProvider.getActive().sessionDao()
        val existingMap = dao.getAll().associateBy { it.id }
        val entities = dtos.map { dto ->
            val domain = dto.toDomain()
            val existing = existingMap[domain.id]
            if (existing != null) {
                val dbStatus = runCatching { SessionStatus.valueOf(existing.status ?: "") }.getOrNull()
                domain.copy(
                    status = dbStatus ?: domain.status,
                    phoneStartedAt = existing.phoneStartedAt,
                    startedAt = existing.startedAt,
                    totalDuration = existing.totalDuration,
                    modelProviderID = existing.modelProviderID,
                    modelID = existing.modelID,
                    modelName = existing.modelName,
                ).toEntity()
            } else {
                domain.toEntity()
            }
        }
        dao.upsertAll(entities)
        return dao.getAll().map { it.toDomain() }
    }

    override suspend fun getSession(id: String): Session? {
        return try {
            val dto = api.getSession(id)
            val domain = dto.toDomain()
            val dao = dbProvider.getActive().sessionDao()
            val existing = dao.getById(id)
            val merged = if (existing != null) {
                val dbStatus = runCatching { SessionStatus.valueOf(existing.status ?: "") }.getOrNull()
                domain.copy(
                    status = dbStatus ?: domain.status,
                    phoneStartedAt = existing.phoneStartedAt,
                    startedAt = existing.startedAt,
                    totalDuration = existing.totalDuration,
                    modelProviderID = existing.modelProviderID,
                    modelID = existing.modelID,
                    modelName = existing.modelName,
                )
            } else {
                domain
            }
            dao.upsert(merged.toEntity())
            dao.getById(id)?.toDomain()
        } catch (_: Exception) {
            dbProvider.getActive().sessionDao().getById(id)?.toDomain()
        }
    }

    override suspend fun createSession(title: String?, directory: String?): Session {
        val dto = api.createSession(title = title, directory = directory)
        val domain = dto.toDomain()
        dbProvider.getActive().sessionDao().upsert(domain.toEntity())
        return domain
    }

    override suspend fun deleteSession(id: String) {
        api.deleteSession(id)
        val dao = dbProvider.getActive().sessionDao()
        dao.delete(id)
    }

    override suspend fun updateSession(id: String, title: String?) {
        api.updateSession(id, title)
        val dao = dbProvider.getActive().sessionDao()
        val existing = dao.getById(id) ?: return
        val updated = if (title != null) existing.copy(title = title) else existing
        dao.upsert(updated)
    }

    override suspend fun abortSession(id: String, directory: String?) {
        api.abortSession(id, directory)
    }

    override suspend fun refreshSessionStatuses() {
        try {
            val statusMap = api.getSessionStatuses()
            val dao = dbProvider.getActive().sessionDao()
            val now = System.currentTimeMillis()
            for ((sessionID, statusDto) in statusMap) {
                val newStatus = statusDto.toDomain().name
                val existing = dao.getById(sessionID)
                if (existing != null) {
                    if (existing.status == newStatus) continue
                    val isBusy = newStatus == SessionStatus.BUSY.name || newStatus == SessionStatus.RUNNING.name
                    val startedAt = when {
                        isBusy && existing.startedAt == null -> now
                        !isBusy -> null
                        else -> existing.startedAt
                    }
                    dao.upsert(existing.copy(status = newStatus, startedAt = startedAt))
                } else {
                    dao.upsert(
                        com.openmate.core.database.entity.SessionEntity(
                            id = sessionID,
                            title = "",
                            directory = "",
                            projectID = "",
                            createdAt = now,
                            updatedAt = now,
                            status = newStatus,
                            startedAt = if (newStatus == SessionStatus.BUSY.name || newStatus == SessionStatus.RUNNING.name) now else null,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SessionRepository", "refreshSessionStatuses failed", e)
        }
    }

    override suspend fun refreshSessionStatusesFromMessages() = refreshSessionStatuses()

    override suspend fun syncSessionStatusFromRemote(sessionID: String) = refreshSessionStatuses()

    override fun observeSessions(directory: String?): Flow<List<Session>> {
        val dao = dbProvider.getActive().sessionDao()
        return if (directory != null) {
            dao.observeByDirectory(directory).map { list -> list.map { it.toDomain() } }
        } else {
            dao.observeAll().map { list -> list.map { it.toDomain() } }
        }
    }

    override fun observeSession(id: String): Flow<Session?> {
        return dbProvider.getActive().sessionDao().observeById(id).map { it?.toDomain() }
    }

    override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> {
        return retryStateStore.observe(id)
    }

    override fun observeWorkspaces(): Flow<List<Workspace>> {
        return dbProvider.getActive().sessionDao().observeAll().map { sessions ->
            sessions
                .groupBy { it.directory }
                .map { (dir, list) ->
                    val sorted = list.sortedByDescending { it.updatedAt }
                    Workspace(
                        directory = dir,
                        sessionCount = list.size,
                        latestUpdatedAt = sorted.first().updatedAt,
                        latestTitle = sorted.first().title,
                    )
                }
                .sortedByDescending { it.latestUpdatedAt }
        }
    }

    override suspend fun addSessionDuration(id: String, increment: Long) {
        dbProvider.getActive().sessionDao().addDuration(id, increment)
    }

    override suspend fun updateSessionModel(id: String, providerID: String?, modelID: String?, modelName: String?) {
        dbProvider.getActive().sessionDao().updateModel(id, providerID, modelID, modelName)
    }

    override suspend fun updateSessionStatus(id: String, status: String) {
        dbProvider.getActive().sessionDao().updateStatus(id, status)
    }

    override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? {
        val status = api.getSessionStatuses()[id] ?: return null
        if (status.type != "retry") return null
        val message = status.message?.takeIf { it.isNotBlank() } ?: return null
        return SessionRetryStatus(
            sessionId = id,
            attempt = status.attempt,
            message = message,
            next = status.next,
        )
    }

    override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) {
        api.revertSession(sessionID, messageID, partID, directory)
    }

    override suspend fun unrevertSession(sessionID: String, directory: String?) {
        api.unrevertSession(sessionID, directory)
    }

    override suspend fun resolveMessageID(sessionID: String, timeCreated: Long): String? {
        return syncApiClient.resolveMessageID(sessionID, timeCreated)
    }

    fun updateObservedRetryStatus(id: String, status: SessionRetryStatus?) {
        retryStateStore.update(id, status)
    }
}
