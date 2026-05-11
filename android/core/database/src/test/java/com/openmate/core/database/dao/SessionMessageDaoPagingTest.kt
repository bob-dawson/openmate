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
