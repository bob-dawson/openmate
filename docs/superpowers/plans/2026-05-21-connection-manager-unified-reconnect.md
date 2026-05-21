# ConnectionManager Unified Reconnect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ConnectionManager` the only owner of Android profile connection and reconnect behavior, including startup restore and profile switching.

**Architecture:** Keep all reconnect and active-profile decisions inside `android/app/ConnectionManager.kt`, trigger startup restore from `OpenMateApp`, and remove page-level reconnect logic from `InstanceListViewModel`. Add focused unit tests around restore selection, duplicate connect suppression, profile switching, and ViewModel behavior.

**Tech Stack:** Kotlin, Coroutines, StateFlow, Hilt, Android Application lifecycle, JUnit4, kotlinx-coroutines-test, Google Truth, Robolectric

---

## File Map

- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Add startup restore entrypoint.
  - Serialize connection attempts.
  - Ignore duplicate same-profile connect requests.
  - Ensure profile switching tears down the old connection before starting the new one.
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
  - Inject `ConnectionManager` and trigger startup restore from `onCreate()`.
- Modify: `android/feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt`
  - Remove `autoReconnect()`.
- Create: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Add focused orchestration tests.
- Create: `android/feature/instance/src/test/java/com/openmate/feature/instance/InstanceListViewModelTest.kt`
  - Add regression tests for no auto reconnect and explicit connect forwarding.

### Task 1: Add failing ConnectionManager orchestration tests

**Files:**
- Create: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
- Reference: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Reference: `android/core/data/src/test/java/com/openmate/core/data/fake/FakeServerProfileRepository.kt`

- [ ] **Step 1: Write the failing test file skeleton**

```kotlin
package com.openmate.app

import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.fake.FakeServerProfileRepository
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.model.SseEvent
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncNotification
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.SyncSseLogger
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionManagerTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun restoreLastConnection_connectsMostRecentProfile() = runTest(dispatcher) {
        val profileA = profile(id = "a", address = "127.0.0.1", port = 4101, lastConnectedAt = 100L)
        val profileB = profile(id = "b", address = "127.0.0.2", port = 4102, lastConnectedAt = 200L)
        val fixture = ConnectionManagerFixture(listOf(profileA, profileB))

        fixture.manager.restoreLastConnection()
        advanceUntilIdle()

        assertThat(fixture.sseRepository.connectCalls).containsExactly("b")
        assertThat(fixture.manager.activeProfile.value?.id).isEqualTo("b")
    }

    @Test
    fun connect_sameProfileWhileAlreadyConnecting_isIgnored() = runTest(dispatcher) {
        val profile = profile(id = "same", address = "127.0.0.3", port = 4103, lastConnectedAt = 100L)
        val fixture = ConnectionManagerFixture(listOf(profile))

        fixture.manager.connect(profile)
        fixture.manager.connect(profile)
        advanceUntilIdle()

        assertThat(fixture.sseRepository.connectCalls).containsExactly("same")
    }

    @Test
    fun connect_switchingProfiles_disconnectsOldBeforeStartingNew() = runTest(dispatcher) {
        val profileA = profile(id = "a", address = "127.0.0.1", port = 4101, lastConnectedAt = 100L)
        val profileB = profile(id = "b", address = "127.0.0.2", port = 4102, lastConnectedAt = 200L)
        val fixture = ConnectionManagerFixture(listOf(profileA, profileB))

        fixture.manager.connect(profileA)
        advanceUntilIdle()
        fixture.manager.connect(profileB)
        advanceUntilIdle()

        assertThat(fixture.sseRepository.events).containsExactly(
            "connect:a",
            "disconnect",
            "connect:b",
        ).inOrder()
        assertThat(fixture.manager.activeProfile.value?.id).isEqualTo("b")
    }

    private class ConnectionManagerFixture(initialProfiles: List<ServerProfile>) {
        val profileRepository = FakeServerProfileRepository().apply {
            initialProfiles.forEach { profile ->
                kotlinx.coroutines.runBlocking { save(profile) }
            }
        }
        val sseRepository = RecordingSseEventRepository(initialProfiles.associateBy { it.id })
        val sessionRepository = FakeSessionRepository()
        val dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication()))
        val apiClient = OpencodeApiClient(client = OkHttpClient(), baseUrl = "http://127.0.0.1:4097")
        val tokenStore = FakeTokenStore()
        val syncSseClient = RecordingSyncSseClient()
        val syncSseHandler = FakeSyncSseHandler()
        val gatewayInterceptor = GatewayInterceptor()
        val logStore = SyncLogStore()

        val manager = ConnectionManager(
            profileRepository = profileRepository,
            sseEventRepository = sseRepository,
            sessionRepository = sessionRepository,
            dbProvider = dbProvider,
            apiClient = apiClient,
            tokenStore = tokenStore,
            syncSseClient = syncSseClient,
            syncSseHandler = syncSseHandler,
            gatewayInterceptor = gatewayInterceptor,
            logStore = logStore,
        )
    }

    private class RecordingSseEventRepository(
        private val profilesById: Map<String, ServerProfile>,
    ) : SseEventRepository {
        val connectCalls = mutableListOf<String>()
        val events = mutableListOf<String>()
        private val statuses = MutableStateFlow(ConnectionStatus.DISCONNECTED)

        override fun connect(address: String, port: Int, password: String?): Flow<SseEvent> {
            val profileId = profilesById.values.first { profile ->
                profile.address == address && profile.port == port
            }.id
            connectCalls += profileId
            events += "connect:$profileId"
            statuses.value = ConnectionStatus.CONNECTING
            return emptyFlow()
        }

        override fun connectViaGateway(baseUrl: String): Flow<SseEvent> {
            connectCalls += "gateway"
            events += "connect:gateway"
            statuses.value = ConnectionStatus.CONNECTING
            return emptyFlow()
        }

        override fun disconnect() {
            events += "disconnect"
            statuses.value = ConnectionStatus.DISCONNECTED
        }

        override fun observeConnectionStatus(): Flow<ConnectionStatus> = statuses
        override fun isConnectedTo(address: String, port: Int): Boolean = false
        override fun setActiveSessionScope(directory: String?, enabled: Boolean) = Unit
        override fun observeMessageSyncNeeded(): Flow<String> = emptyFlow()
        override fun observeSessionErrors(): Flow<Pair<String, String>> = emptyFlow()
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
        override fun observeWorkspaces(): Flow<List<Workspace>> = flowOf(emptyList())
        override suspend fun addSessionDuration(id: String, increment: Long) = Unit
        override suspend fun updateSessionModel(id: String, providerID: String?, modelID: String?, modelName: String?) = Unit
        override suspend fun updateSessionStatus(id: String, status: String) = Unit
        override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? = null
        override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) = Unit
        override suspend fun unrevertSession(sessionID: String, directory: String?) = Unit
        override suspend fun resolveMessageID(sessionID: String, timeCreated: Long): String? = null
        override suspend fun resolveEvtID(sessionID: String, messageID: String): String? = null
    }

    private class FakeTokenStore : TokenStore(RuntimeEnvironment.getApplication())

    private class RecordingSyncSseClient : SyncSseClient(
        client = OkHttpClient(),
        tokenStore = FakeTokenStore(),
        logger = object : SyncSseLogger {
            override fun logConnectStart(traceId: String, hasToken: Boolean) = Unit
            override fun logConnectSuccess(traceId: String, costMs: Long) = Unit
            override fun logDisconnected(traceId: String?, currentBaseUrl: String?) = Unit
            override fun logConnectFailure(traceId: String, error: Throwable) = Unit
            override fun logStreamClosed(traceId: String) = Unit
            override fun logNotification(sessionId: String, seq: Long, traceId: String) = Unit
        },
    ) {
        val connectCalls = mutableListOf<String>()
        private val notificationsFlow = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 8)
        override val notifications: SharedFlow<SyncNotification> = notificationsFlow

        override suspend fun connect(baseUrl: String) {
            connectCalls += baseUrl
        }

        override fun disconnect(traceId: String?) = Unit
    }

    private class FakeSyncSseHandler : com.openmate.core.data.sync.SyncSseHandler(
        syncSseClient = RecordingSyncSseClient(),
        repository = object : com.openmate.core.domain.repository.SessionMessageRepository {
            override fun observeMessages(sessionId: String) = emptyFlow<com.openmate.core.domain.model.SessionMessage>()
            override fun observeSyncEvents() = emptyFlow<com.openmate.core.domain.model.SessionMessageSyncEvent>()
            override suspend fun getRecentWindow(sessionId: String, limit: Int) = emptyList<com.openmate.core.domain.model.SessionMessage>()
            override suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int) = emptyList<com.openmate.core.domain.model.SessionMessage>()
            override suspend fun getOlderPageByUserTurns(sessionId: String, beforeTimeCreated: Long, beforeId: String, userTurns: Int) = emptyList<com.openmate.core.domain.model.SessionMessage>()
            override suspend fun findBusyStartTime(sessionId: String): Long? = null
            override suspend fun initSync(sessionId: String, limit: Int) = throw UnsupportedOperationException()
            override suspend fun incrementalSync(sessionId: String) = Unit
            override suspend fun incrementalSyncAndNotify(sessionId: String) = Unit
            override suspend fun fetchFullMessage(sessionId: String, messageId: String) = Unit
            override suspend fun getLastSeq(sessionId: String): Long? = null
            override suspend fun rollbackSeq(sessionId: String, count: Long) = Unit
            override suspend fun deleteMessage(sessionId: String, messageId: String) = Unit
        },
        logStore = SyncLogStore(),
    )

    private fun profile(id: String, address: String, port: Int, lastConnectedAt: Long?) = ServerProfile(
        id = id,
        name = id,
        address = address,
        port = port,
        createdAt = 1L,
        lastConnectedAt = lastConnectedAt,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: FAIL because `restoreLastConnection()` does not exist yet and the production code still allows duplicate or overlapping connect flows.

- [ ] **Step 3: Keep the test fixture minimal if production constructors make subclassing awkward**

```kotlin
// If TokenStore / SyncSseClient / SyncSseHandler are not open enough for direct subclassing,
// replace inheritance with the smallest real objects that do not perform network work,
// and keep all connect-order assertions on RecordingSseEventRepository.
// The key assertion surface for this task is:
// - which profile connect() was started for
// - whether disconnect() happened before the next profile connect()
```

- [ ] **Step 4: Run the test again to verify it still fails for the intended production gaps**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: FAIL with assertions showing missing startup restore behavior or duplicate connect behavior in `ConnectionManager`.

### Task 2: Implement ConnectionManager as the single reconnect owner

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Add the startup restore API required by tests**

```kotlin
@Volatile
private var restoreStarted = false

fun restoreLastConnection() {
    if (restoreStarted) return
    restoreStarted = true
    scope.launch {
        val profile = profileRepository.getAll()
            .filter { it.lastConnectedAt != null }
            .maxByOrNull { it.lastConnectedAt ?: Long.MIN_VALUE }
            ?: return@launch
        connect(profile)
    }
}
```

- [ ] **Step 2: Add connection attempt guards and profile-switch teardown**

```kotlin
private val connectRequestId = java.util.concurrent.atomic.AtomicLong(0)
@Volatile
private var activeConnectRequestId: Long = 0

override fun connect(profile: ServerProfile) {
    val currentProfile = _activeProfile.value
    val currentStatus = _connectionStatus.value
    val sameProfile = currentProfile?.id == profile.id
    val alreadyActive = sameProfile && currentStatus in setOf(
        ConnectionStatus.CONNECTING,
        ConnectionStatus.CONNECTED,
        ConnectionStatus.GATEWAY_CONNECTED,
    )
    if (alreadyActive) return

    val requestId = connectRequestId.incrementAndGet()
    activeConnectRequestId = requestId

    scope.launch {
        if (currentProfile?.id != null && currentProfile.id != profile.id) {
            teardownCurrentConnection(clearActiveProfile = false)
        }
        startConnection(profile, requestId)
    }
}
```

- [ ] **Step 3: Split teardown and guarded status publishing into helpers**

```kotlin
private fun isCurrentRequest(requestId: Long): Boolean = activeConnectRequestId == requestId

private fun publishStatus(status: ConnectionStatus, requestId: Long) {
    if (!isCurrentRequest(requestId)) return
    _connectionStatus.value = status
    _isConnected.value = status == ConnectionStatus.CONNECTED ||
        status == ConnectionStatus.GATEWAY_CONNECTED
}

private fun teardownCurrentConnection(clearActiveProfile: Boolean) {
    useGateway = false
    stopDirectCheckLoop()
    sseEventRepository.disconnect()
    syncSseJob?.cancel()
    syncSseClient.disconnect()
    gatewayInterceptor.instanceId = null
    if (clearActiveProfile) {
        scope.launch { clearConnection() }
    }
}
```

- [ ] **Step 4: Route existing connect flow through `startConnection(profile, requestId)`**

```kotlin
private suspend fun startConnection(profile: ServerProfile, requestId: Long) {
    publishStatus(ConnectionStatus.CONNECTING, requestId)
    _errorMessage.value = null
    _needsRepairing.value = null
    _activeProfile.value = profile

    tokenStore.setActiveProfileId(profile.id)
    dbProvider.setActive(profile.id)

    // existing direct/gateway selection stays here

    if (!isCurrentRequest(requestId)) return
    startSseConnections(profile)
    val savedProfile = _activeProfile.value ?: profile
    profileRepository.save(savedProfile.copy(lastConnectedAt = System.currentTimeMillis()))
}
```

- [ ] **Step 5: Guard status application from the SSE observer**

```kotlin
init {
    scope.launch {
        sseEventRepository.observeConnectionStatus().collect { status ->
            val mapped = if (status == ConnectionStatus.CONNECTED && useGateway) {
                ConnectionStatus.GATEWAY_CONNECTED
            } else {
                status
            }
            if (_activeProfile.value == null) return@collect
            _connectionStatus.value = mapped
            _isConnected.value = mapped == ConnectionStatus.CONNECTED ||
                mapped == ConnectionStatus.GATEWAY_CONNECTED
            if (mapped == ConnectionStatus.CONNECTED || mapped == ConnectionStatus.GATEWAY_CONNECTED) {
                sessionRepository.refreshSessionStatuses()
            }
        }
    }
}
```

- [ ] **Step 6: Run ConnectionManager tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: PASS for restore, duplicate connect suppression, and profile switching order.

### Task 3: Move startup restore into the application lifecycle

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Inject `ConnectionManager` into `OpenMateApp` and trigger restore**

```kotlin
@HiltAndroidApp
class OpenMateApp : Application() {
    @Inject lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        connectionManager.restoreLastConnection()
    }
}
```

- [ ] **Step 2: Add a smoke test for repeated restore calls being harmless**

```kotlin
@Test
fun restoreLastConnection_calledTwice_onlyStartsOneActiveProfileConnect() = runTest(dispatcher) {
    val profile = profile(id = "restore", address = "127.0.0.9", port = 4109, lastConnectedAt = 300L)
    val fixture = ConnectionManagerFixture(listOf(profile))

    fixture.manager.restoreLastConnection()
    fixture.manager.restoreLastConnection()
    advanceUntilIdle()

    assertThat(fixture.sseRepository.connectCalls).containsExactly("restore")
}
```

- [ ] **Step 3: Run the app module tests again**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: PASS, including startup restore idempotence.

### Task 4: Remove ViewModel-owned auto reconnect and lock it with regression tests

**Files:**
- Modify: `android/feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt`
- Create: `android/feature/instance/src/test/java/com/openmate/feature/instance/InstanceListViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel regression tests**

```kotlin
package com.openmate.feature.instance

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.fake.FakeServerProfileRepository
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InstanceListViewModelTest {
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
    fun init_doesNotAutoConnect() = runTest(dispatcher) {
        val connectionRepository = RecordingConnectionRepository()
        val viewModel = InstanceListViewModel(
            profileRepository = FakeServerProfileRepository(),
            dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication())),
            connectionManager = connectionRepository,
        )

        advanceUntilIdle()

        assertThat(connectionRepository.connectCalls).isEmpty()
        assertThat(viewModel.profiles.value).isEmpty()
    }

    @Test
    fun connect_forwardsSelectedProfile() = runTest(dispatcher) {
        val profile = ServerProfile(
            id = "selected",
            name = "Selected",
            address = "127.0.0.1",
            createdAt = 1L,
        )
        val connectionRepository = RecordingConnectionRepository()
        val viewModel = InstanceListViewModel(
            profileRepository = FakeServerProfileRepository(),
            dbProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication())),
            connectionManager = connectionRepository,
        )

        viewModel.connect(profile)

        assertThat(connectionRepository.connectCalls).containsExactly("selected")
    }

    private class RecordingConnectionRepository : ConnectionRepository {
        val connectCalls = mutableListOf<String>()
        override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.DISCONNECTED).asStateFlow()
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
```

- [ ] **Step 2: Run the ViewModel test to verify it fails**

Run: `./gradlew.bat :feature:instance:testDebugUnitTest --tests "com.openmate.feature.instance.InstanceListViewModelTest" --no-daemon`

Expected: FAIL because `autoReconnect()` still invokes `connect()` during init.

- [ ] **Step 3: Remove `autoReconnect()` from the ViewModel and keep explicit connect intact**

```kotlin
@HiltViewModel
class InstanceListViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val connectionManager: ConnectionRepository,
) : ViewModel() {

    init {
        observeCombined()
    }

    fun connect(profile: ServerProfile, onNavigate: () -> Unit = {}) {
        onNavigate()
        connectionManager.connect(profile)
    }
}
```

- [ ] **Step 4: Run the ViewModel test again**

Run: `./gradlew.bat :feature:instance:testDebugUnitTest --tests "com.openmate.feature.instance.InstanceListViewModelTest" --no-daemon`

Expected: PASS, confirming no init-time reconnect and correct explicit connect forwarding.

### Task 5: Final verification

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
- Modify: `android/feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
- Test: `android/feature/instance/src/test/java/com/openmate/feature/instance/InstanceListViewModelTest.kt`

- [ ] **Step 1: Run the focused unit tests together**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: PASS.

- [ ] **Step 2: Run the feature-specific ViewModel tests**

Run: `./gradlew.bat :feature:instance:testDebugUnitTest --tests "com.openmate.feature.instance.InstanceListViewModelTest" --no-daemon`

Expected: PASS.

- [ ] **Step 3: Run a broader regression build for touched Android modules**

Run: `./gradlew.bat :app:assembleDebug :feature:instance:testDebugUnitTest --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect the final diff before any implementation commit**

Run: `git diff -- android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/main/java/com/openmate/app/OpenMateApp.kt android/feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt android/feature/instance/src/test/java/com/openmate/feature/instance/InstanceListViewModelTest.kt`

Expected: Only the unified reconnect ownership changes and matching tests appear.
