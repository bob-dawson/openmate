package com.openmate.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncApiClient
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication()))
        dbProvider.setActive(PROFILE_ID)
        val apiClient = OpencodeApiClient(OkHttpClient(), baseUrl = server.url("/").toString().removeSuffix("/"))
        repository = SessionMessageRepositoryImpl(
            syncApiClient = SyncApiClient(OkHttpClient(), apiClient),
            dbProvider = dbProvider,
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

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val SESSION_ID = "session-1"
    }
}
