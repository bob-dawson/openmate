package com.openmate.core.domain.repository

import com.openmate.core.domain.model.TodoInfo
import kotlinx.coroutines.flow.Flow

interface TodoRepository {
    fun observeTodos(sessionID: String): Flow<List<TodoInfo>>
    suspend fun refreshTodos(sessionID: String)
}
