package com.openmate.core.data.sse

import com.google.common.truth.Truth.assertThat
import com.openmate.core.network.SseData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test

class EventDispatcherTest {
    private var lastPermissionEventType: String? = null
    private var lastQuestionEventType: String? = null
    private lateinit var dispatcher: EventDispatcher

    private val stubDbProvider = object : com.openmate.core.database.ActiveDatabaseProvider() {
        override fun getActive() = throw UnsupportedOperationException()
        override fun getActiveProfileId() = null
    }

    private val stubApi = com.openmate.core.network.OpencodeApiClient(
        client = okhttp3.OkHttpClient(),
        baseUrl = "http://localhost",
    )

    @Before
    fun setup() {
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(dbProvider = stubDbProvider),
            messageHandler = object : MessageEventHandler(api = stubApi, dbProvider = stubDbProvider) {
                override suspend fun handle(type: String, event: SseData) {}
            },
            permissionHandler = object : PermissionEventHandler(api = stubApi, dbProvider = stubDbProvider) {
                override suspend fun handle(type: String, event: SseData) {
                    lastPermissionEventType = type
                }
            },
            questionHandler = object : QuestionEventHandler(api = stubApi, dbProvider = stubDbProvider) {
                override suspend fun handle(type: String, event: SseData) {
                    lastQuestionEventType = type
                }
            },
            todoHandler = object : TodoEventHandler(dbProvider = stubDbProvider) {
                override suspend fun handle(type: String, event: SseData) {}
            },
        )
    }

    @Test
    fun dispatch_routesPermissionEvent() = runTest {
        dispatcher.dispatch(makeEvent("permission.requested"))
        assertThat(lastPermissionEventType).isEqualTo("permission.requested")
    }

    @Test
    fun dispatch_routesQuestionEvent() = runTest {
        dispatcher.dispatch(makeEvent("question.requested"))
        assertThat(lastQuestionEventType).isEqualTo("question.requested")
    }

    @Test
    fun dispatch_serverHeartbeat_noHandlerCalled() = runTest {
        dispatcher.dispatch(makeEvent("server.heartbeat"))
        assertThat(lastPermissionEventType).isNull()
        assertThat(lastQuestionEventType).isNull()
    }

    private fun makeEvent(type: String): SseData {
        return SseData(
            type = type,
            properties = buildJsonObject { put("type", type) },
        )
    }
}
