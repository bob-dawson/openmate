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

        dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication()))
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
    fun incrementalSync_returnsInsertedAndUpdatedMessages() = runTest {
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
        dbProvider.getActive().syncStateDao().upsert(SyncStateEntity(sessionId = SESSION_ID, lastSeq = 5L))

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "events": [
                    {
                      "id": "evt-1",
                      "aggregateId": "session-1",
                      "seq": 6,
                      "type": "session.next.text.started.event",
                      "data": {
                        "timestamp": 3
                      }
                    },
                    {
                      "id": "evt-2",
                      "aggregateId": "session-1",
                      "seq": 7,
                      "type": "session.next.text.ended.event",
                      "data": {
                        "timestamp": 4,
                        "text": "updated text"
                      }
                    },
                    {
                      "id": "m3",
                      "aggregateId": "session-1",
                      "seq": 8,
                      "type": "session.next.prompted.event",
                      "data": {
                        "timestamp": 5,
                        "prompt": {
                          "text": "new prompt",
                          "files": [],
                          "agents": []
                        }
                      }
                    }
                  ],
                  "maxSeq": 8
                }
                """.trimIndent(),
            ),
        )

        val result = repository.incrementalSync(SESSION_ID)

        assertThat(result.changes.map { it.javaClass.simpleName }).containsExactly("Update", "Insert").inOrder()
        assertThat(result.lastSeq).isEqualTo(8L)
        assertThat(dbProvider.getActive().sessionMessageDao().getById("m1")?.data).contains("updated text")
        assertThat(dbProvider.getActive().sessionMessageDao().getById("m3")?.data).contains("new prompt")
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
    fun incrementalSync_marksRunningToolAbortedWhenStepFails() = runTest {
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
        dbProvider.getActive().syncStateDao().upsert(SyncStateEntity(sessionId = SESSION_ID, lastSeq = 1L))

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "events": [
                    {
                      "id": "evt-tool-input-started",
                      "aggregateId": "session-1",
                      "seq": 2,
                      "type": "session.next.tool.input.started.event",
                      "data": {
                        "timestamp": 2,
                        "callID": "call-1",
                        "name": "bash"
                      }
                    },
                    {
                      "id": "evt-tool-called",
                      "aggregateId": "session-1",
                      "seq": 3,
                      "type": "session.next.tool.called.event",
                      "data": {
                        "timestamp": 3,
                        "callID": "call-1",
                        "input": {"cmd":"pwd"},
                        "provider": {}
                      }
                    },
                    {
                      "id": "evt-step-failed",
                      "aggregateId": "session-1",
                      "seq": 4,
                      "type": "session.next.step.failed.event",
                      "data": {
                        "timestamp": 4,
                        "error": {"message":"MessageAbortedError"}
                      }
                    }
                  ],
                  "maxSeq": 4
                }
                """.trimIndent(),
            ),
        )

        repository.incrementalSync(SESSION_ID)

        val stored = dbProvider.getActive().sessionMessageDao().getById("assistant-1")!!
        assertThat(stored.completedAt).isEqualTo(4L)
        assertThat(stored.data).contains("\"status\":\"error\"")
        assertThat(stored.data).contains("Tool execution aborted")
    }

    @Test
    fun incrementalSync_completesExistingCompactionWhenEndedArrivesLater() = runTest {
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
                      "id": "compaction-1",
                      "sessionId": "session-1",
                      "type": "compaction",
                      "timeCreated": 1,
                      "timeUpdated": 1,
                      "data": {
                        "reason": "manual",
                        "summary": "",
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
        dbProvider.getActive().syncStateDao().upsert(SyncStateEntity(sessionId = SESSION_ID, lastSeq = 1L))

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "events": [
                    {
                      "id": "evt-ended",
                      "aggregateId": "session-1",
                      "seq": 2,
                      "type": "session.next.compaction.ended.event",
                      "data": {
                        "timestamp": 4,
                        "text": "condensed prior context"
                      }
                    }
                  ],
                  "maxSeq": 2
                }
                """.trimIndent(),
            ),
        )

        repository.incrementalSync(SESSION_ID)

        val stored = dbProvider.getActive().sessionMessageDao().getById("compaction-1")!!
        assertThat(stored.completedAt).isEqualTo(4L)
        assertThat(stored.data).contains("condensed prior context")
        assertThat(stored.data).contains("\"completed\":4")
    }

    @Test
    fun incrementalSync_logsPackageBytesAndPerEventBytes() = runTest {
        val responseBody =
            """
            {
              "events": [
                {
                  "id": "evt-11",
                  "aggregateId": "session-1",
                  "seq": 11,
                  "type": "session.next.prompted.event",
                  "data": {
                    "timestamp": 5,
                    "prompt": {
                      "text": "new prompt",
                      "files": [],
                      "agents": []
                    }
                  }
                }
              ],
              "maxSeq": 11
            }
            """.trimIndent()
        val rawEventJson =
            """
            {
                  "id": "evt-11",
                  "aggregateId": "session-1",
                  "seq": 11,
                  "type": "session.next.prompted.event",
                  "data": {
                    "timestamp": 5,
                    "prompt": {
                      "text": "new prompt",
                      "files": [],
                      "agents": []
                    }
                  }
                }
            """.trimIndent()
        val expectedPackageBytes = responseBody.toByteArray(StandardCharsets.UTF_8).size
        val expectedEventBytes = rawEventJson.toByteArray(StandardCharsets.UTF_8).size

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
        dbProvider.getActive().syncStateDao().upsert(SyncStateEntity(sessionId = SESSION_ID, lastSeq = 10L))

        server.enqueue(
            MockResponse().setBody(responseBody),
        )

        repository.incrementalSync(SESSION_ID)

        val rendered = logStore.entries.value.map { it.renderedText }
        assertThat(rendered.any { it.contains("增量包返回") && it.contains("bytes=$expectedPackageBytes") }).isTrue()
        assertThat(rendered.any { it.contains("增量消息处理") && it.contains("seq=11") && it.contains("bytes=$expectedEventBytes") }).isTrue()
        assertThat(rendered.any { it.contains("增量同步结束") && it.contains("totalBytes=$expectedPackageBytes") && it.contains("bytes=$expectedPackageBytes") }).isTrue()
    }

    @Test
    fun incrementalSync_logsFailureWhenEventsFetchFailsBeforeReplay() = runTest {
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
        dbProvider.getActive().syncStateDao().upsert(SyncStateEntity(sessionId = SESSION_ID, lastSeq = 10L))

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
        assertThat(rendered.any { it.contains("afterSeq=10") }).isTrue()
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val SESSION_ID = "session-1"
    }
}
