package com.openmate.core.data.repository

import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class TodoRepositoryImpl @Inject constructor(
) : TodoRepository {

    override fun observeTodos(sessionID: String): Flow<List<TodoInfo>> = flowOf(emptyList())

    override suspend fun refreshTodos(sessionID: String) {}
}
