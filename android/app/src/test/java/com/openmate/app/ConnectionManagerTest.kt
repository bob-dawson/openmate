package com.openmate.app

import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.SyncSseLogger
import com.openmate.core.network.TokenStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConnectionManagerTest {

    private val managers = mutableListOf<ConnectionManager>()

    @After
    fun tearDown() {
        managers.forEach { it.disconnect() }
        Thread.sleep(50)
    }

    @Test
    fun connect_gatewayProfile_startsGatewaySseAndMarksProfileActive() {
        val env = createEnvironment()
        val profile = profile(name = "gateway", instanceId = "iid-gateway")

        env.manager.connect(profile)

        waitUntil { env.sseEventRepository.gatewayConnectCount == 1 }

        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profile.id)
        assertThat(env.apiClient.baseUrl).isEqualTo("https://gateway.clawmate.net")
        assertThat(env.gatewayInterceptor.instanceId).isEqualTo(profile.instanceId)
        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(1)
        assertThat(env.profileRepository.savedProfiles).hasSize(1)
    }

    @Test
    fun connectionManager_exposesRestoreLastConnectionEntrypoint() {
        val methodNames = ConnectionManager::class.java.methods.map { it.name }

        assertThat(methodNames).contains("restoreLastConnection")
    }

    @Test
    fun connect_sameGatewayProfileTwice_doesNotStartSecondSseCycle() {
        val env = createEnvironment()
        val profile = profile(name = "dup", instanceId = "iid-dup")

        env.manager.connect(profile)
        waitUntil { env.sseEventRepository.gatewayConnectCount == 1 }

        env.manager.connect(profile)
        Thread.sleep(200)

        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(1)
    }

    private fun createEnvironment(): TestEnvironment {
        val profileRepository = FakeServerProfileRepository()
        val sseEventRepository = FakeSseEventRepository()
        val sessionRepository = FakeSessionRepository()
        val databaseProvider = ActiveDatabaseProvider(DatabaseFactory(RuntimeEnvironment.getApplication()))
        val gatewayInterceptor = GatewayInterceptor()
        val apiClient = OpencodeApiClient(OkHttpClient.Builder().addInterceptor(gatewayInterceptor).build())
        val tokenStore = TokenStore(RuntimeEnvironment.getApplication())
        val syncSseClient = SyncSseClient(
            client = ImmediateCancellationCallFactory(),
            tokenStore = tokenStore,
            logger = NoOpSyncSseLogger,
        )
        val syncSseHandler = SyncSseHandler(
            syncSseClient = syncSseClient,
            repository = FakeSessionMessageRepository(),
            logStore = SyncLogStore(),
        )
        val manager = ConnectionManager(
            profileRepository = profileRepository,
            sseEventRepository = sseEventRepository,
            sessionRepository = sessionRepository,
            dbProvider = databaseProvider,
            apiClient = apiClient,
            tokenStore = tokenStore,
            syncSseClient = syncSseClient,
            syncSseHandler = syncSseHandler,
            gatewayInterceptor = gatewayInterceptor,
            logStore = SyncLogStore(),
        )
        managers += manager
        return TestEnvironment(
            manager = manager,
            profileRepository = profileRepository,
            sseEventRepository = sseEventRepository,
            apiClient = apiClient,
            gatewayInterceptor = gatewayInterceptor,
        )
    }

    private fun profile(
        name: String,
        instanceId: String,
    ): ServerProfile {
        val id = "$name-${System.nanoTime()}"
        return ServerProfile(
            id = id,
            name = name,
            address = "127.0.0.1",
            port = 4097,
            instanceId = instanceId,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun waitUntil(timeoutMs: Long = 2_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return
            }
            Thread.sleep(10)
        }
        check(predicate())
    }

    private data class TestEnvironment(
        val manager: ConnectionManager,
        val profileRepository: FakeServerProfileRepository,
        val sseEventRepository: FakeSseEventRepository,
        val apiClient: OpencodeApiClient,
        val gatewayInterceptor: GatewayInterceptor,
    )

    private class FakeServerProfileRepository : ServerProfileRepository {
        val savedProfiles = mutableListOf<ServerProfile>()
        private val profilesFlow = MutableStateFlow<List<ServerProfile>>(emptyList())

        override fun observeAll(): Flow<List<ServerProfile>> = profilesFlow

        override suspend fun getAll(): List<ServerProfile> = profilesFlow.value

        override suspend fun getById(id: String): ServerProfile? = profilesFlow.value.find { it.id == id }

        override suspend fun save(profile: ServerProfile) {
            savedProfiles += profile
            profilesFlow.value = profilesFlow.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun delete(id: String) {
            profilesFlow.value = profilesFlow.value.filterNot { it.id == id }
        }
    }

    private class FakeSseEventRepository : SseEventRepository {
        private val statusFlow = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        var directConnectCount = 0
        var gatewayConnectCount = 0
        var disconnectCount = 0

        override fun connect(address: String, port: Int, password: String?): Flow<com.openmate.core.domain.model.SseEvent> {
            directConnectCount += 1
            return emptyFlow()
        }

        override fun connectViaGateway(baseUrl: String): Flow<com.openmate.core.domain.model.SseEvent> {
            gatewayConnectCount += 1
            return emptyFlow()
        }

        override fun disconnect() {
            disconnectCount += 1
        }

        override fun observeConnectionStatus(): Flow<ConnectionStatus> = statusFlow

        override fun isConnectedTo(address: String, port: Int): Boolean = false

        override fun setActiveSessionScope(directory: String?, enabled: Boolean) = Unit

        override fun observeMessageSyncNeeded(): Flow<String> = emptyFlow()

        override fun observeSessionErrors(): Flow<Pair<String, String>> = emptyFlow()
    }

    private class FakeSessionRepository : SessionRepository {
        private val refreshCalls = AtomicInteger(0)

        val refreshSessionStatusesCount: Int
            get() = refreshCalls.get()

        override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> = emptyList()

        override suspend fun getSession(id: String): Session? = null

        override suspend fun createSession(title: String?, directory: String?): Session {
            error("Unexpected createSession call")
        }

        override suspend fun deleteSession(id: String) = Unit

        override suspend fun updateSession(id: String, title: String?) = Unit

        override suspend fun abortSession(id: String, directory: String?) = Unit

        override suspend fun refreshSessionStatuses(directory: String?) {
            refreshCalls.incrementAndGet()
        }

        override suspend fun syncSessionStatusFromRemote(sessionID: String) = Unit

        override fun observeSessions(directory: String?): Flow<List<Session>> = flowOf(emptyList())

        override fun observeSession(id: String): Flow<Session?> = flowOf(null)

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

    private class FakeSessionMessageRepository : SessionMessageRepository {
        override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> = flowOf(emptyList())

        override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = MutableSharedFlow()

        override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> = emptyList()

        override suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int): List<SessionMessage> = emptyList()

        override suspend fun getOlderPageByUserTurns(sessionId: String, beforeTimeCreated: Long, beforeId: String, userTurns: Int): List<SessionMessage> = emptyList()

        override suspend fun findBusyStartTime(sessionId: String): Long? = null

        override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult {
            return SessionMessageSyncResult(lastSeq = 0L, changes = emptyList())
        }

        override suspend fun incrementalSync(sessionId: String) = Unit

        override suspend fun incrementalSyncAndNotify(sessionId: String) = Unit

        override suspend fun fetchFullMessage(sessionId: String, messageId: String) = Unit

        override suspend fun getLastSeq(sessionId: String): Long? = null

        override suspend fun rollbackSeq(sessionId: String, count: Long) = Unit

        override suspend fun deleteMessage(sessionId: String, messageId: String) = Unit
    }

    private class ImmediateCancellationCallFactory : Call.Factory {
        override fun newCall(request: Request): Call = ImmediateCancellationCall(request)
    }

    private class ImmediateCancellationCall(
        private val requestValue: Request,
    ) : Call {
        override fun request(): Request = requestValue

        override fun execute(): okhttp3.Response {
            throw CancellationException("stop test sse loop")
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = ImmediateCancellationCall(requestValue)
    }

    private object NoOpSyncSseLogger : SyncSseLogger {
        override fun logConnectStart(traceId: String, hasToken: Boolean) = Unit

        override fun logConnectSuccess(traceId: String, costMs: Long) = Unit

        override fun logDisconnected(traceId: String?, currentBaseUrl: String?) = Unit

        override fun logConnectFailure(traceId: String, error: Throwable) = Unit

        override fun logStreamClosed(traceId: String) = Unit

        override fun logNotification(sessionId: String, seq: Long, traceId: String) = Unit
    }
}
