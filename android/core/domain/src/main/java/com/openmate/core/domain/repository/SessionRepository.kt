package com.openmate.core.domain.repository

import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.Workspace
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session>
    suspend fun getSession(id: String): Session?
    suspend fun createSession(title: String?, directory: String? = null): Session
    suspend fun deleteSession(id: String)
    suspend fun updateSession(id: String, title: String?)
    suspend fun abortSession(id: String, directory: String? = null)
    suspend fun refreshSessionStatuses()
    suspend fun refreshSessionStatusesFromMessages()
    suspend fun syncSessionStatusFromRemote(sessionID: String)
    fun observeSessions(directory: String?): Flow<List<Session>>
    fun observeSession(id: String): Flow<Session?>
    fun observeWorkspaces(): Flow<List<Workspace>>
}
