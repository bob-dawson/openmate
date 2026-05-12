package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.network.SseData
import javax.inject.Inject

class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val todoHandler: TodoEventHandler,
) {
    var activeDirectory: String = ""
        set(value) {
            field = value
            permissionHandler.activeDirectory = value
            questionHandler.activeDirectory = value
        }
    var messageSyncEnabled: Boolean = false

    suspend fun dispatch(event: SseData) {
        val type = event.type
        val dir = event.directory
        Log.d("EventDispatcher", "dispatch: type=$type dir=$dir activeDir=$activeDirectory enabled=$messageSyncEnabled")

        if (type == "server.connected" || type == "server.heartbeat" || type == "global.disposed") {
            return
        }

        val isMessageScoped =
            type.startsWith("message.") ||
                type.startsWith("todo.") ||
                type.startsWith("session.next.")

        if (isMessageScoped) {
            if (!messageSyncEnabled) {
                Log.d("EventDispatcher", "skipped: message sync disabled type=$type")
                return
            }
            if (dir != null && activeDirectory.isNotBlank() && dir != activeDirectory) {
                Log.d("EventDispatcher", "skipped: dir mismatch dir=$dir activeDir=$activeDirectory")
                return
            }
        }

        when {
            type.startsWith("session.") -> sessionHandler.handle(type, event)
            type.startsWith("message.") -> { /* handled by SessionMessageEventHandler via SessionMessageRepository */ }
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type.startsWith("todo.") -> todoHandler.handle(type, event)
        }
    }
}
