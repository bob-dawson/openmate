package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.network.SseData
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class TodoEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
) {
    suspend fun handle(type: String, event: SseData) {
        when (type) {
            "todo.updated" -> handleUpdated(event)
        }
    }

    private suspend fun handleUpdated(event: SseData) {
        try {
            val props = event.properties
            val sessionID = props["sessionID"]?.jsonPrimitive?.content ?: return
            val todosArray = props["todos"]?.jsonArray ?: return
            val todos = todosArray.map { element ->
                val obj = element.jsonObject
                TodoInfo(
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    status = obj["status"]?.jsonPrimitive?.content ?: "pending",
                    priority = obj["priority"]?.jsonPrimitive?.content ?: "medium",
                )
            }
            val db = dbProvider.getActive()
            db.todoDao().upsert(todos.toEntity(sessionID))
        } catch (e: Exception) {
            Log.w("TodoEventHandler", "todo.updated failed", e)
        }
    }
}
