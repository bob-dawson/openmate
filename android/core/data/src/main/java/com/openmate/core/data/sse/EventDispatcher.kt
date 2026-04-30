package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.network.SseData
import javax.inject.Inject

class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val todoHandler: TodoEventHandler,
) {
    var activeDirectory: String? = null

    suspend fun dispatch(event: SseData) {
        val type = event.type
        val dir = event.directory
        Log.d("EventDispatcher", "dispatch: type=$type dir=$dir activeDir=$activeDirectory")

        if (type == "server.connected" || type == "server.heartbeat" || type == "global.disposed") {
            return
        }

        if (dir != null && activeDirectory != null && dir != activeDirectory) {
            Log.d("EventDispatcher", "skipped: dir mismatch dir=$dir activeDir=$activeDirectory")
            return
        }

        when {
            type.startsWith("session.") -> sessionHandler.handle(type, event)
            type.startsWith("message.") -> messageHandler.handle(type, event)
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type.startsWith("todo.") -> todoHandler.handle(type, event)
        }
    }
}
