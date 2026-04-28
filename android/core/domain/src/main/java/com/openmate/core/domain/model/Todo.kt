package com.openmate.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TodoInfo(
    val content: String,
    val status: String,
    val priority: String,
)

data class TodoList(
    val sessionID: String,
    val todos: List<TodoInfo>,
)
