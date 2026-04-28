package com.openmate.core.data.sse

import com.openmate.core.network.SseData
import javax.inject.Inject

class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
) {
    fun dispatch(event: SseData) {
        val type = event.type
        when {
            type.startsWith("session.") -> sessionHandler.handle(type, event)
            type.startsWith("message.") -> messageHandler.handle(type, event)
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type == "server.connected" -> {}
            type == "server.heartbeat" -> {}
        }
    }
}
