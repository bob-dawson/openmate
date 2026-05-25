package com.openmate.feature.session

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionSnapshot
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.OpencodeApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkspaceAndSessionConnectionStatusTest {

    @get:Rule
    val instantTaskExecutorRule: TestRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun workspaceListViewModel_usesConnectionRepositoryStatus() = runTest(dispatcher) {
        val viewModel = WorkspaceListViewModel(
            sessionRepository = FakeSessionRepository(),
            connectionRepository = FakeConnectionRepository(ConnectionStatus.GATEWAY_CONNECTED),
            profileRepository = FakeServerProfileRepository(),
            dbProvider = dbProvider(),
            apiClient = apiClient(),
        )

        advanceUntilIdle()

        assertThat(viewModel.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
    }

    @Test
    fun sessionListViewModel_usesConnectionRepositoryStatus() = runTest(dispatcher) {
        val viewModel = SessionListViewModel(
            sessionRepository = FakeSessionRepository(),
            connectionRepository = FakeConnectionRepository(ConnectionStatus.GATEWAY_CONNECTED),
        )

        viewModel.setDirectory("/workspace")
        advanceUntilIdle()

        assertThat(viewModel.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
    }

    private fun apiClient() = OpencodeApiClient(client = OkHttpClient(), baseUrl = "http://test")

    private fun dbProvider() = ActiveDatabaseProvider(RuntimeEnvironment.getApplication(), DatabaseFactory(RuntimeEnvironment.getApplication())).apply {
        setActive("profile-default")
    }

    private class FakeConnectionRepository(status: ConnectionStatus) : ConnectionRepository {
        override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(status).asStateFlow()
        override val connectionSnapshot: StateFlow<ConnectionSnapshot?> = MutableStateFlow(null).asStateFlow()
        override val activeProfile: StateFlow<ServerProfile?> = MutableStateFlow(null).asStateFlow()
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val errorMessage: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val needsRepairing: StateFlow<String?> = MutableStateFlow(null).asStateFlow()

        override fun connect(profile: ServerProfile) = Unit
        override fun reconnect() = Unit
        override fun disconnect() = Unit
        override fun confirmRepairing(profileId: String, token: String) = Unit
        override fun clearNeedsRepairing() = Unit
        override fun clearError() = Unit
    }

    private class FakeSessionRepository : SessionRepository {
        private val session = Session(
            id = "session-1",
            title = "Session",
            directory = "/workspace",
            projectID = "project",
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.IDLE,
        )

        override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> = listOf(session)
        override suspend fun getSession(id: String): Session? = session
        override suspend fun createSession(title: String?, directory: String?): Session = session
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun updateSession(id: String, title: String?) = Unit
        override suspend fun abortSession(id: String, directory: String?) = Unit
        override suspend fun refreshSessionStatuses(directory: String?) = Unit
        override suspend fun syncSessionStatusFromRemote(sessionID: String) = Unit
        override fun observeSessions(directory: String?): Flow<List<Session>> = flowOf(listOf(session))
        override fun observeSession(id: String): Flow<Session?> = flowOf(session)
        override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> = flowOf(null)
        override fun observeWorkspaces(): Flow<List<Workspace>> = flowOf(listOf(Workspace("/workspace", 1, 1, "Session")))
        override suspend fun addSessionDuration(id: String, increment: Long) = Unit
        override suspend fun updateSessionModel(id: String, providerID: String?, modelID: String?, modelName: String?) = Unit
        override suspend fun updateSessionStatus(id: String, status: String) = Unit
        override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? = null
        override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) = Unit
        override suspend fun unrevertSession(sessionID: String, directory: String?) = Unit
        override suspend fun resolveMessageID(sessionID: String, timeCreated: Long): String? = null
        override suspend fun resolveEvtID(sessionID: String, messageID: String): String? = null
    }

    private class FakeServerProfileRepository : ServerProfileRepository {
        override fun observeAll(): Flow<List<ServerProfile>> = flowOf(emptyList())
        override suspend fun getAll(): List<ServerProfile> = emptyList()
        override suspend fun getById(id: String): ServerProfile? = null
        override suspend fun save(profile: ServerProfile) = Unit
        override suspend fun delete(id: String) = Unit
    }
}
