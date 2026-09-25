package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.data.sync.SubtaskSessionTracker
import com.openmate.core.network.SseData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val todoHandler: TodoEventHandler,
    private val subtaskSessionTracker: SubtaskSessionTracker,
) {
    private val _messageSyncNeeded = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageSyncNeeded: SharedFlow<String> = _messageSyncNeeded

    private val _sessionErrors = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val sessionErrors: SharedFlow<Pair<String, String>> = _sessionErrors

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

        // V2 exposes a running subagent's child session id only through the ephemeral
        // session.tool.progress metadata, so remember it by tool call id for navigation.
        if (type == "session.tool.progress") {
            val callId = event.properties["id"]?.jsonPrimitive?.contentOrNull
                ?: event.properties["callID"]?.jsonPrimitive?.contentOrNull
            val meta = event.properties["metadata"]?.jsonObject
            val child = meta?.get("sessionID")?.jsonPrimitive?.contentOrNull
                ?: meta?.get("sessionId")?.jsonPrimitive?.contentOrNull
            if (callId != null && child != null) {
                subtaskSessionTracker.record(callId, child)
                Log.d("EventDispatcher", "subtask progress: call=$callId child=$child")
            }
        }

        val isMessageScoped =
            type.startsWith("message.") ||
                type.startsWith("todo.") ||
                type.startsWith("session.text.") ||
                type.startsWith("session.reasoning.") ||
                type.startsWith("session.tool.") ||
                type.startsWith("session.step.") ||
                type.startsWith("session.compaction.") ||
                type.startsWith("session.shell.") ||
                type.startsWith("session.revert.") ||
                type.startsWith("session.input.") ||
                type.startsWith("session.execution.") ||
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
            type.startsWith("session.created") ||
            type.startsWith("session.updated") ||
            type.startsWith("session.deleted") ||
            type.startsWith("session.status") ||
            type.startsWith("session.error") ||
            type.startsWith("session.renamed") -> {
                val result = sessionHandler.handle(type, event)
                if (result != null) {
                    _sessionErrors.tryEmit(result)
                }
            }
            type.startsWith("message.") -> {}
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type.startsWith("form.") -> questionHandler.handle(type, event)
            type.startsWith("todo.") -> todoHandler.handle(type, event)
        }
    }
}
