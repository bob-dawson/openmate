package com.openmate.core.data.sse

import com.google.common.truth.Truth.assertThat
import com.openmate.core.network.SseData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test

class EventDispatcherTest {
    private var lastSessionEventType: String? = null
    private var lastMessageEventType: String? = null
    private var lastPermissionEventType: String? = null
    private var lastQuestionEventType: String? = null
    private lateinit var dispatcher: EventDispatcher

    @Before
    fun setup() {
        dispatcher = EventDispatcher(
            sessionHandler = object : SessionEventHandler() {
                override fun handle(type: String, event: SseData) {
                    lastSessionEventType = type
                }
            },
            messageHandler = object : MessageEventHandler() {
                override fun handle(type: String, event: SseData) {
                    lastMessageEventType = type
                }
            },
            permissionHandler = object : PermissionEventHandler() {
                override fun handle(type: String, event: SseData) {
                    lastPermissionEventType = type
                }
            },
            questionHandler = object : QuestionEventHandler() {
                override fun handle(type: String, event: SseData) {
                    lastQuestionEventType = type
                }
            },
        )
    }

    @Test
    fun dispatch_routesSessionEvent() {
        dispatcher.dispatch(makeEvent("session.created"))
        assertThat(lastSessionEventType).isEqualTo("session.created")
    }

    @Test
    fun dispatch_routesMessageEvent() {
        dispatcher.dispatch(makeEvent("message.updated"))
        assertThat(lastMessageEventType).isEqualTo("message.updated")
    }

    @Test
    fun dispatch_routesPermissionEvent() {
        dispatcher.dispatch(makeEvent("permission.asked"))
        assertThat(lastPermissionEventType).isEqualTo("permission.asked")
    }

    @Test
    fun dispatch_routesQuestionEvent() {
        dispatcher.dispatch(makeEvent("question.asked"))
        assertThat(lastQuestionEventType).isEqualTo("question.asked")
    }

    @Test
    fun dispatch_serverHeartbeat_noHandlerCalled() {
        dispatcher.dispatch(makeEvent("server.heartbeat"))
        assertThat(lastSessionEventType).isNull()
        assertThat(lastMessageEventType).isNull()
    }

    private fun makeEvent(type: String): SseData {
        return SseData(
            type = type,
            properties = buildJsonObject { put("type", type) },
        )
    }
}
