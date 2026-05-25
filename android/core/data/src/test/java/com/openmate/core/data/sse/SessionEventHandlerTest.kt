package com.openmate.core.data.sse

import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.database.entity.SessionEntity
import com.openmate.core.network.SseData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionEventHandlerTest {

    private lateinit var dbProvider: ActiveDatabaseProvider
    private lateinit var retryStateStore: SessionRetryStateStore
    private lateinit var handler: SessionEventHandler

    @Before
    fun setUp() {
        dbProvider = ActiveDatabaseProvider(RuntimeEnvironment.getApplication(), DatabaseFactory(RuntimeEnvironment.getApplication()))
        dbProvider.setActive(PROFILE_ID)
        retryStateStore = SessionRetryStateStore()
        handler = SessionEventHandler(dbProvider = dbProvider, retryStateStore = retryStateStore, api = com.openmate.core.network.OpencodeApiClient(okhttp3.OkHttpClient()))
    }

    @After
    fun tearDown() {
        dbProvider.clearActive()
        DatabaseFactory(RuntimeEnvironment.getApplication()).delete(RuntimeEnvironment.getApplication(), PROFILE_ID)
    }

    @Test
    fun handle_sessionStatusRetry_updatesObservedRetryState() = runTest {
        dbProvider.getActive().sessionDao().upsert(SessionEntity(
            id = SESSION_ID,
            title = "test",
            directory = "/test",
            projectID = "proj",
            createdAt = 0L,
            updatedAt = 0L,
        ))
        handler.handle(
            type = "session.status",
            event = sseEvent(
                sessionId = SESSION_ID,
                statusType = "retry",
                attempt = 2,
                message = "rate limited",
                next = 1000L,
            ),
        )

        assertThat(retryStateStore.observe(SESSION_ID).first()?.message).isEqualTo("rate limited")
        assertThat(retryStateStore.observe(SESSION_ID).first()?.attempt).isEqualTo(2)

        handler.handle(
            type = "session.status",
            event = sseEvent(
                sessionId = SESSION_ID,
                statusType = "busy",
            ),
        )

        assertThat(retryStateStore.observe(SESSION_ID).first()).isNull()
    }

    private fun sseEvent(
        sessionId: String,
        statusType: String,
        attempt: Int? = null,
        message: String? = null,
        next: Long? = null,
    ): SseData {
        return SseData(
            type = "session.status",
            properties = buildJsonObject {
                put("sessionID", sessionId)
                put("status", buildJsonObject {
                    put("type", statusType)
                    if (attempt != null) put("attempt", attempt)
                    if (message != null) put("message", message)
                    if (next != null) put("next", next)
                })
            },
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val SESSION_ID = "session-1"
    }
}
