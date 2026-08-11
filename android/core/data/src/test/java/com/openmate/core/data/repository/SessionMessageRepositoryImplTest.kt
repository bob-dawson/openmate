package com.openmate.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncApiClient
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionMessageRepositoryImplTest {

    private lateinit var dbProvider: ActiveDatabaseProvider
    private lateinit var repository: SessionMessageRepositoryImpl
    private lateinit var server: MockWebServer
    private lateinit var logStore: SyncLogStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        dbProvider = ActiveDatabaseProvider(RuntimeEnvironment.getApplication(), DatabaseFactory(RuntimeEnvironment.getApplication()))
        dbProvider.setActive(PROFILE_ID)
        val apiClient = OpencodeApiClient(OkHttpClient(), baseUrl = server.url("/").toString().removeSuffix("/"))
        logStore = SyncLogStore()
        repository = SessionMessageRepositoryImpl(
            syncApiClient = SyncApiClient(OkHttpClient(), apiClient),
            dbProvider = dbProvider,
            logStore = logStore,
        )
    }

    @After
    fun tearDown() {
        dbProvider.clearActive()
        DatabaseFactory(RuntimeEnvironment.getApplication()).delete(RuntimeEnvironment.getApplication(), PROFILE_ID)
        server.shutdown()
    }

    @Test
    fun incrementalSync_insertsNewMessagesFromMessagesEndpoint() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"events":[],"maxSeq":5}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [
                    {
                      "id": "m1",
                      "sessionId": "session-1",
                      "type": "assistant",
                      "timeCreated": 1,
                      "timeUpdated": 1,
                      "data": {
                        "agent": "openmate",
                        "model": {},
                        "content": [],
                        "time": {
                          "created": 1
                        }
                      }
                    }
                  ],
                  "maxSeq": 5
                }
                """.trimIndent(),
            ),
        )
        repository.initSync(SESSION_ID, limit = 30)

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [
                    {
                      "id": "m2",
                      "sessionId": "session-1",
                      "type": "user",
                      "timeCreated": 2,
                      "timeUpdated": 2,
                      "data": {
                        "text": "hello"
                      }
                    },
                    {
                      "id": "m3",
                      "sessionId": "session-1",
                      "type": "assistant",
                      "timeCreated": 3,
                      "timeUpdated": 3,
                      "data": {
                        "agent": "openmate",
                        "model": {},
                        "content": [{"type":"text","text":"hi back"}],
                        "time": {"created": 3, "completed": 3},
                        "finish": "stop"
                      }
                    }
                  ],
                  "deletedIds": [],
                  "hasMore": false,
                  "maxSeq": 8
                }
                """.trimIndent(),
            ),
        )

        repository.incrementalSync(SESSION_ID)

        assertThat(dbProvider.getActive().sessionMessageDao().getById("m1")?.id).isEqualTo("m1")
        assertThat(dbProvider.getActive().sessionMessageDao().getById("m2")?.data).contains("hello")
        assertThat(dbProvider.getActive().sessionMessageDao().getById("m3")?.data).contains("hi back")
    }

    @Test
    fun windowQueries_returnRecentWindowAndOlderPage() = runTest {
        dbProvider.getActive().sessionMessageDao().upsertAll(
            (1L..5L).map { timeCreated ->
                com.openmate.core.database.entity.SessionMessageEntity(
                    id = "m$timeCreated",
                    sessionId = SESSION_ID,
                    type = "user",
                    data = "{\"text\":\"m$timeCreated\"}",
                    timeCreated = timeCreated,
                    timeUpdated = timeCreated,
                )
            },
        )

        val recent = repository.getRecentWindow(SESSION_ID, limit = 3)
        val older = repository.getOlderPage(
            sessionId = SESSION_ID,
            beforeTimeCreated = recent.first().timeCreated,
            beforeId = recent.first().id,
            limit = 2,
        )

        assertThat(recent.map { it.id }).containsExactly("m3", "m4", "m5").inOrder()
        assertThat(older.map { it.id }).containsExactly("m1", "m2").inOrder()
    }

    @Test
    fun incrementalSync_updatesExistingMessage() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"events":[],"maxSeq":1}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [
                    {
                      "id": "assistant-1",
                      "sessionId": "session-1",
                      "type": "assistant",
                      "timeCreated": 1,
                      "timeUpdated": 1,
                      "data": {
                        "agent": "openmate",
                        "model": {},
                        "content": [],
                        "time": {
                          "created": 1
                        }
                      }
                    }
                  ],
                  "maxSeq": 1
                }
                """.trimIndent(),
            ),
        )
        repository.initSync(SESSION_ID, limit = 30)

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [
                    {
                      "id": "assistant-1",
                      "sessionId": "session-1",
                      "type": "assistant",
                      "timeCreated": 1,
                      "timeUpdated": 2,
                      "data": {
                        "agent": "openmate",
                        "model": {},
                        "content": [{"type":"text","text":"updated text"}],
                        "time": {"created": 1, "completed": 2},
                        "finish": "stop"
                      }
                    }
                  ],
                  "deletedIds": [],
                  "hasMore": false,
                  "maxSeq": 2
                }
                """.trimIndent(),
            ),
        )

        repository.incrementalSync(SESSION_ID)

        val stored = dbProvider.getActive().sessionMessageDao().getById("assistant-1")!!
        assertThat(stored.data).contains("updated text")
        assertThat(stored.completedAt).isEqualTo(2L)
    }

    @Test
    fun incrementalSync_deletesMessagesFromDeletedIds() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"events":[],"maxSeq":1}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [
                    {
                      "id": "m1",
                      "sessionId": "session-1",
                      "type": "user",
                      "timeCreated": 1,
                      "timeUpdated": 1,
                      "data": {"text":"hello"}
                    },
                    {
                      "id": "m2",
                      "sessionId": "session-1",
                      "type": "assistant",
                      "timeCreated": 2,
                      "timeUpdated": 2,
                      "data": {"content":[],"time":{"created":2}}
                    }
                  ],
                  "maxSeq": 1
                }
                """.trimIndent(),
            ),
        )
        repository.initSync(SESSION_ID, limit = 30)

        assertThat(dbProvider.getActive().sessionMessageDao().getById("m2")).isNotNull()

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [],
                  "deletedIds": ["m2"],
                  "hasMore": false,
                  "maxSeq": 2
                }
                """.trimIndent(),
            ),
        )

        repository.incrementalSync(SESSION_ID)

        assertThat(dbProvider.getActive().sessionMessageDao().getById("m2")).isNull()
        assertThat(dbProvider.getActive().sessionMessageDao().getById("m1")).isNotNull()
    }

    @Test
    fun incrementalSync_logsPackageBytes() = runTest {
        val responseBody =
            """
            {
              "messages": [
                {
                  "id": "m5",
                  "sessionId": "session-1",
                  "type": "user",
                  "timeCreated": 5,
                  "timeUpdated": 5,
                  "data": {"text":"new prompt"}
                }
              ],
              "deletedIds": [],
              "hasMore": false,
              "maxSeq": 11
            }
            """.trimIndent()
        val expectedPackageBytes = responseBody.toByteArray(StandardCharsets.UTF_8).size

        server.enqueue(
            MockResponse().setBody(
                """
                {"events":[],"maxSeq":10}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [],
                  "maxSeq": 10
                }
                """.trimIndent(),
            ),
        )
        repository.initSync(SESSION_ID, limit = 30)

        server.enqueue(MockResponse().setBody(responseBody))

        repository.incrementalSync(SESSION_ID)

        val rendered = logStore.entries.value.map { it.renderedText }
        assertThat(rendered.any { it.contains("增量包返回") && it.contains("bytes=$expectedPackageBytes") }).isTrue()
        assertThat(rendered.any { it.contains("增量同步结束") }).isTrue()
    }

    @Test
    fun incrementalSync_logsFailureWhenMessagesFetchFails() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"events":[],"maxSeq":10}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "messages": [],
                  "maxSeq": 10
                }
                """.trimIndent(),
            ),
        )
        repository.initSync(SESSION_ID, limit = 30)

        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("bridge exploded"),
        )

        val result = runCatching {
            repository.incrementalSync(SESSION_ID)
        }

        assertThat(result.exceptionOrNull()).isNotNull()
        val rendered = logStore.entries.value.map { it.renderedText }
        assertThat(rendered.any { it.contains("增量同步失败") }).isTrue()
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val SESSION_ID = "session-1"
    }
}
