package com.openmate.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.sse.SessionRetryStateStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.network.OpencodeApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryImplTest {

    private lateinit var dbProvider: ActiveDatabaseProvider
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setUp() {
        dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication()))
        dbProvider.setActive(PROFILE_ID)
        repository = SessionRepositoryImpl(
            api = OpencodeApiClient(OkHttpClient(), baseUrl = "http://127.0.0.1:4096"),
            dbProvider = dbProvider,
            retryStateStore = SessionRetryStateStore(),
        )
    }

    @After
    fun tearDown() {
        dbProvider.clearActive()
        DatabaseFactory(RuntimeEnvironment.getApplication()).delete(RuntimeEnvironment.getApplication(), PROFILE_ID)
    }

    @Test
    fun observeSessionRetryStatus_emitsUpdatedRetryState() = runTest {
        val values = mutableListOf<SessionRetryStatus?>()
        val expected = SessionRetryStatus(
            sessionId = SESSION_ID,
            attempt = 2,
            message = "rate limited",
            next = 123L,
        )

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeSessionRetryStatus(SESSION_ID).collect { values += it }
        }

        advanceUntilIdle()
        repository.updateObservedRetryStatus(SESSION_ID, expected)
        advanceUntilIdle()
        job.cancel()

        assertThat(values).containsExactly(null, expected).inOrder()
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val SESSION_ID = "session-1"
    }
}
