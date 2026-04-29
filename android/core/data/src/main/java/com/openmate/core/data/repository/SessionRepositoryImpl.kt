package com.openmate.core.data.repository

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.SessionStatusDto
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : SessionRepository {

    override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> {
        val dtos = api.listSessions(directory, limit, start)
        val dao = dbProvider.getActive().sessionDao()
        val existingMap = dao.getAll().associateBy { it.id }
        val entities = dtos.map { dto ->
            val domain = dto.toDomain()
            val existing = existingMap[domain.id]
            val existingStatus = existing?.status
            if (existingStatus != null) {
                val dbStatus = runCatching { SessionStatus.valueOf(existingStatus) }.getOrNull()
                domain.copy(status = dbStatus)
            } else {
                domain
            }.toEntity()
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
            val existingStatus = existing?.status
            val merged = if (existingStatus != null) {
                val dbStatus = runCatching { SessionStatus.valueOf(existingStatus) }.getOrNull()
                domain.copy(status = dbStatus)
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

    override suspend fun abortSession(id: String) {
        api.abortSession(id)
    }

    override suspend fun refreshSessionStatuses() {
        try {
            val statusMap = api.getSessionStatuses()
            val dao = dbProvider.getActive().sessionDao()
            for ((sessionID, statusDto) in statusMap) {
                val existing = dao.getById(sessionID)
                val statusName = statusDto.toDomain().name
                if (existing != null) {
                    dao.upsert(existing.copy(status = statusName))
                } else {
                    dao.upsert(
                        com.openmate.core.database.entity.SessionEntity(
                            id = sessionID,
                            title = "",
                            directory = "",
                            projectID = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            status = statusName,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SessionRepository", "refreshSessionStatuses failed", e)
        }
    }

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
}
