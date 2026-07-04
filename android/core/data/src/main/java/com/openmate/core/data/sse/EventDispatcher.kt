package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.network.SseData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

data class LivePartEvent(
    val sessionId: String,
    val messageId: String,
    val partId: String,
    val eventType: String,
    val partType: String?,
    val text: String?,
    val properties: JsonObject,
)

class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val todoHandler: TodoEventHandler,
) {
    private val _messageSyncNeeded = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageSyncNeeded: SharedFlow<String> = _messageSyncNeeded

    private val _sessionErrors = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val sessionErrors: SharedFlow<Pair<String, String>> = _sessionErrors

    private val _livePartEvents = MutableSharedFlow<LivePartEvent>(extraBufferCapacity = 64)
    val livePartEvents: SharedFlow<LivePartEvent> = _livePartEvents

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
            val sessionId = event.properties["sessionID"]?.jsonPrimitive?.content
            if (sessionId != null) {
                _messageSyncNeeded.tryEmit(sessionId)
            }
        }

        when {
            type.startsWith("session.") -> {
                val result = sessionHandler.handle(type, event)
                if (result != null) {
                    _sessionErrors.tryEmit(result)
                }
            }
            type.startsWith("message.") -> {
                if (type.startsWith("message.part.")) {
                    val sessionId = event.properties["sessionID"]?.jsonPrimitive?.content ?: return
                    val messageId = event.properties["messageID"]?.jsonPrimitive?.content ?: return
                    val partId = event.properties["partID"]?.jsonPrimitive?.content ?: return
                    val partType = event.properties["type"]?.jsonPrimitive?.contentOrNull
                    val text = event.properties["text"]?.jsonPrimitive?.contentOrNull
                    val liveType = when {
                        type.endsWith(".delta") -> "delta"
                        type.endsWith(".updated") -> "updated"
                        type.endsWith(".removed") -> "removed"
                        else -> return
                    }
                    _livePartEvents.tryEmit(LivePartEvent(sessionId, messageId, partId, liveType, partType, text, event.properties))
                }
            }
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type.startsWith("todo.") -> todoHandler.handle(type, event)
        }
    }
}
