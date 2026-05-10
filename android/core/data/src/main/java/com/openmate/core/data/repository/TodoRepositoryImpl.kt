package com.openmate.core.data.repository

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.network.OpencodeApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TodoRepositoryImpl @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
) : TodoRepository {

    override fun observeTodos(sessionID: String): Flow<List<TodoInfo>> {
        return dbProvider.getActive().todoDao().observeBySession(sessionID)
            .map { entity -> entity?.toDomain() ?: emptyList() }
    }

    override suspend fun refreshTodos(sessionID: String) {
        try {
            val todos = apiClient.getTodos(sessionID)
            val db = dbProvider.getActive()
            if (todos.isEmpty()) {
                db.todoDao().deleteBySession(sessionID)
            } else {
                db.todoDao().upsert(todos.toEntity(sessionID))
            }
        } catch (e: Exception) {
            android.util.Log.e("TodoRepository", "refreshTodos failed for session=$sessionID", e)
        }
    }
}
