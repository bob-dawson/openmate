package com.openmate.feature.instance

import com.google.common.truth.Truth.assertThat
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ConnectionSnapshot
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InstanceListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_doesNotAutoConnect() = runTest(dispatcher) {
        val profileRepository = FakeServerProfileRepository().apply {
            save(profile(id = "profile-1"))
        }
        val connectionRepository = RecordingConnectionRepository()
        val viewModel = InstanceListViewModel(
            profileRepository = profileRepository,
            dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication())),
            connectionManager = connectionRepository,
        )

        advanceUntilIdle()

        assertThat(connectionRepository.connectCalls).isEmpty()
        assertThat(viewModel.profiles.value.map { it.profile.id }).containsExactly("profile-1")
    }

    @Test
    fun connect_forwardsSelectedProfile() = runTest(dispatcher) {
        val profile = profile(id = "selected")
        val connectionRepository = RecordingConnectionRepository()
        val viewModel = InstanceListViewModel(
            profileRepository = FakeServerProfileRepository(),
            dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication())),
            connectionManager = connectionRepository,
        )

        viewModel.connect(profile)

        assertThat(connectionRepository.connectCalls).containsExactly("selected")
    }

    private fun profile(id: String) = ServerProfile(
        id = id,
        name = id,
        address = "127.0.0.1",
        createdAt = 1L,
    )

    private class FakeServerProfileRepository : ServerProfileRepository {
        private val profiles = mutableListOf<ServerProfile>()
        private val profilesFlow = MutableStateFlow<List<ServerProfile>>(emptyList())

        override fun observeAll(): Flow<List<ServerProfile>> = profilesFlow.asStateFlow()

        override suspend fun getAll(): List<ServerProfile> = profiles.toList()

        override suspend fun getById(id: String): ServerProfile? = profiles.find { it.id == id }

        override suspend fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
            profilesFlow.value = profiles.toList()
        }

        override suspend fun delete(id: String) {
            profiles.removeAll { it.id == id }
            profilesFlow.value = profiles.toList()
        }
    }

    private class RecordingConnectionRepository : ConnectionRepository {
        val connectCalls = mutableListOf<String>()
        override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.DISCONNECTED).asStateFlow()
        override val connectionSnapshot: StateFlow<ConnectionSnapshot?> = MutableStateFlow(null).asStateFlow()
        override val activeProfile: StateFlow<ServerProfile?> = MutableStateFlow(null).asStateFlow()
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val errorMessage: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val needsRepairing: StateFlow<String?> = MutableStateFlow(null).asStateFlow()

        override fun connect(profile: ServerProfile) {
            connectCalls += profile.id
        }

        override fun reconnect() = Unit

        override fun disconnect() = Unit

        override fun confirmRepairing(profileId: String, token: String) = Unit

        override fun clearNeedsRepairing() = Unit

        override fun clearError() = Unit
    }
}
