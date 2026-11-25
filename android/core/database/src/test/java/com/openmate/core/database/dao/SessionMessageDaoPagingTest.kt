package com.openmate.core.database.dao

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.AppDatabase
import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionMessageDaoPagingTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SessionMessageDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).build()
        dao = database.sessionMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getRecentWindow_returnsOnlyLatestMessagesAscending() = runTest {
        dao.upsertAll((1L..5L).map { timeCreated -> message(id = "m$timeCreated", timeCreated = timeCreated) })

        val result = dao.getRecentWindow(sessionId = SESSION_ID, limit = 3)

        assertThat(result.map { it.id }).containsExactly("m3", "m4", "m5").inOrder()
    }

    @Test
    fun getOlderPage_returnsMessagesBeforeWindowStart() = runTest {
        dao.upsertAll((1L..6L).map { timeCreated -> message(id = "m$timeCreated", timeCreated = timeCreated) })

        val result = dao.getOlderPage(
            sessionId = SESSION_ID,
            beforeTimeCreated = 4L,
            beforeId = "m4",
            limit = 2,
        )

        assertThat(result.map { it.id }).containsExactly("m2", "m3").inOrder()
    }

    @Test
    fun countAssistantMessagesMissingParts_countsOnlyEmptyContentInWindow() = runTest {
        dao.upsertAll(
            listOf(
                assistantMessage("a1", timeCreated = 1, data = "{\"content\":[]}"),
                assistantMessage("a2", timeCreated = 2, data = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"),
                assistantMessage("a3", timeCreated = 3, data = "{\"content\":[]}"),
                SessionMessageEntity(
                    id = "u1",
                    sessionId = SESSION_ID,
                    type = "user",
                    data = "{\"content\":[]}",
                    timeCreated = 4,
                    timeUpdated = 4,
                ),
            )
        )

        val count = dao.countAssistantMessagesMissingParts(SESSION_ID, limit = 10)

        assertThat(count).isEqualTo(2)
    }

    @Test
    fun countAssistantMessagesMissingParts_ignoresMessagesOutsideWindow() = runTest {
        dao.upsertAll(
            listOf(
                assistantMessage("old", timeCreated = 1, data = "{\"content\":[]}"),
                assistantMessage("new", timeCreated = 2, data = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"),
            )
        )

        val count = dao.countAssistantMessagesMissingParts(SESSION_ID, limit = 1)

        assertThat(count).isEqualTo(0)
    }

    private fun assistantMessage(id: String, timeCreated: Long, data: String) = SessionMessageEntity(
        id = id,
        sessionId = SESSION_ID,
        type = "assistant",
        data = data,
        timeCreated = timeCreated,
        timeUpdated = timeCreated,
    )

    private fun message(
        id: String,
        timeCreated: Long,
    ) = SessionMessageEntity(
        id = id,
        sessionId = SESSION_ID,
        type = "user",
        data = "{}",
        timeCreated = timeCreated,
        timeUpdated = timeCreated,
    )

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
