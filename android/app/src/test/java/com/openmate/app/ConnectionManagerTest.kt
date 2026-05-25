package com.openmate.app

import com.google.common.truth.Truth.assertThat
import com.openmate.app.connection.AppForegroundMonitor
import com.openmate.app.connection.NetworkChangeMonitor
import com.openmate.app.connection.RouteEvidence
import com.openmate.app.connection.RouteEvidenceAggregator
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute
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
import com.openmate.core.network.BridgeEvent
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.RouteEvidenceReporter
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.SyncSseLogger
import com.openmate.core.network.TokenStore
import com.openmate.core.network.dto.BridgeInfoDto
import com.openmate.core.network.dto.BridgeOpencodeInfoDto
import com.openmate.core.network.dto.BridgeStatusResponse
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
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
    fun connect_profileWithInstanceId_prefersDirectWhenDirectBridgeReachable() {
        val profile = profile(name = "gateway", instanceId = "iid-gateway")
        val env = createEnvironment(
            directBridgeReachable = true,
            directBridgeInstanceId = profile.instanceId,
        )

        env.manager.connect(profile)

        waitUntil { env.syncSseCallFactory.requestCount == 1 }

        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profile.id)
        assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
        assertThat(env.gatewayInterceptor.instanceId).isNull()
        assertThat(env.sseEventRepository.directConnectCount).isEqualTo(0)
        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(0)
        assertThat(env.syncSseCallFactory.requests.single().url.toString()).isEqualTo("http://${profile.address}:${profile.port}/api/bridge/events")
        assertThat(env.profileRepository.savedProfiles).hasSize(1)
    }

    @Test
    fun connect_profileWithInstanceId_fallsBackToGatewayWhenDirectBridgeUnreachable() {
        val env = createEnvironment(directBridgeReachable = false)
        val profile = profile(name = "gateway-fallback", instanceId = "iid-fallback")

        env.manager.connect(profile)

        waitUntil { env.syncSseCallFactory.requestCount == 1 }

        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profile.id)
        assertThat(env.apiClient.baseUrl).isEqualTo("https://gateway.clawmate.net")
        assertThat(env.gatewayInterceptor.instanceId).isEqualTo(profile.instanceId)
        assertThat(env.sseEventRepository.directConnectCount).isEqualTo(0)
        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(0)
        assertThat(env.syncSseCallFactory.requests.single().url.toString()).isEqualTo("https://gateway.clawmate.net/api/bridge/events")
    }

    @Test
    fun connectionManager_exposesRestoreLastConnectionEntrypoint() {
        val methodNames = ConnectionManager::class.java.methods.map { it.name }

        assertThat(methodNames).contains("restoreLastConnection")
    }

    @Test
    fun restoreLastConnection_connectsMostRecentProfile() {
        val profileA = profile(name = "restore-a", instanceId = "iid-restore")
            .copy(lastConnectedAt = 100L)
        val profileB = profile(name = "restore-b", instanceId = "iid-restore")
            .copy(lastConnectedAt = 200L)
        val env = createEnvironment(directBridgeInstanceId = "iid-restore")
        runBlocking {
            env.profileRepository.save(profileA)
            env.profileRepository.save(profileB)
        }

        env.manager.restoreLastConnection()

        waitUntil { env.syncSseCallFactory.requestCount == 1 }

        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profileB.id)
    }

    @Test
    fun restoreLastConnection_calledTwice_onlyStartsOneActiveProfileConnect() {
        val profile = profile(name = "restore-once", instanceId = "iid-restore-once")
            .copy(lastConnectedAt = 300L)
        val env = createEnvironment(directBridgeInstanceId = "iid-restore-once")
        runBlocking {
            env.profileRepository.save(profile)
        }

        env.manager.restoreLastConnection()
        env.manager.restoreLastConnection()

        waitUntil { env.syncSseCallFactory.requestCount == 1 }
        Thread.sleep(100)

        assertThat(env.syncSseCallFactory.requestCount).isEqualTo(1)
        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profile.id)
    }

    @Test
    fun connect_sameGatewayProfileTwice_doesNotStartSecondSseCycle() {
        val env = createEnvironment()
        val profile = profile(name = "dup", instanceId = "iid-dup")

        env.manager.connect(profile)
        waitUntil { env.syncSseCallFactory.requestCount == 1 }

        env.manager.connect(profile)
        Thread.sleep(200)

        assertThat(env.syncSseCallFactory.requestCount).isEqualTo(1)
        assertThat(env.sseEventRepository.directConnectCount).isEqualTo(0)
        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(0)
    }

    @Test
    fun connect_startsOnlyBridgeBusinessSse() {
        val env = createEnvironment()
        val profile = profile(name = "single-sse", instanceId = "iid-single")

        env.manager.connect(profile)

        waitUntil { env.syncSseCallFactory.requestCount == 1 }

        assertThat(env.sseEventRepository.directConnectCount).isEqualTo(0)
        assertThat(env.sseEventRepository.gatewayConnectCount).isEqualTo(0)
        assertThat(env.syncSseCallFactory.requestCount).isEqualTo(1)
        assertThat(env.syncSseCallFactory.requests.single().url.encodedPath).isEqualTo("/api/bridge/events")
    }

    @Test
    fun connect_switchingProfiles_cancelsOldSseBeforeStartingNewProfile() {
        val callFactory = BlockingSseCallFactory()
        val env = createEnvironment(
            directBridgeInstanceId = "iid-switch-a",
            syncSseCallFactory = callFactory,
        )
        val profileA = profile(name = "switch-a", instanceId = "iid-switch-a")
        val profileB = profile(name = "switch-b", instanceId = "iid-switch-a")

        env.manager.connect(profileA)
        waitUntil { callFactory.startedCount == 1 }

        env.manager.connect(profileB)

        waitUntil { callFactory.requestCount == 2 && callFactory.cancelCount >= 1 }

        val cancelFirstIndex = callFactory.events.indexOf("cancel:1")
        val secondRequestIndex = callFactory.events.indexOf("new:2")
        assertThat(cancelFirstIndex).isAtLeast(0)
        assertThat(secondRequestIndex).isAtLeast(0)
        assertThat(cancelFirstIndex).isLessThan(secondRequestIndex)
        assertThat(env.manager.activeProfile.value?.id).isEqualTo(profileB.id)
    }

    @Test
    fun sseEventReceived_onDirect_keepsDirectUsable() {
        val profile = profile(name = "sse-positive", instanceId = "iid-direct")
        val env = createEnvironment(
            directBridgeReachable = true,
            directBridgeInstanceId = profile.instanceId,
            syncSseCallFactory = BlockingSseCallFactory(
                initialBody = "data: {\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"ses_1\"}}\n\n"
            ),
        )

        env.manager.connect(profile)

        waitUntil { env.manager.connectionStatus.value == ConnectionStatus.CONNECTED }

        assertThat(env.manager.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTED)
        assertThat(env.manager.connectionSnapshot.value?.isUsable).isTrue()
        assertThat(env.manager.connectionSnapshot.value?.activeRoute)
            .isEqualTo(ConnectionRoute.Direct(profile.address, profile.port))
        assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
    }

    @Test
    fun sseFailure_onDirect_entersRecoveringWithoutImmediateGatewaySwitch() {
        val profile = profile(name = "sse-fail", instanceId = "iid-direct")
        val env = createEnvironment(
            directBridgeReachable = true,
            directBridgeInstanceId = profile.instanceId,
            syncSseCallFactory = FailingSseCallFactory(),
        )

        env.manager.connect(profile)

        waitUntil { env.manager.connectionSnapshot.value?.phase == ConnectionPhase.RECOVERING }

        assertThat(env.manager.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTING)
        assertThat(env.manager.connectionSnapshot.value?.phase).isEqualTo(ConnectionPhase.RECOVERING)
        assertThat(env.manager.connectionSnapshot.value?.activeRoute)
            .isEqualTo(ConnectionRoute.Direct(profile.address, profile.port))
        assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
    }

    @Test
    fun gatewayToDirectHandoff_doesNotPublishDisconnectedDuringShortRestart() {
        val profile = profile(name = "handoff", instanceId = "iid-handoff")
        val callFactory = BlockingSseCallFactory()
        val env = createEnvironment(
            directBridgeReachable = false,
            syncSseCallFactory = callFactory,
        )
        val history = Collections.synchronizedList(mutableListOf<ConnectionStatus>())
        val collectorJob = CoroutineScope(Dispatchers.Default).launch {
            env.manager.connectionStatus.collect { history += it }
        }

        try {
            env.manager.connect(profile)

            waitUntil { env.manager.connectionStatus.value == ConnectionStatus.GATEWAY_CONNECTED }

            env.routeEvidenceAggregator.record(
                RouteEvidence.ProbeSuccess(
                    route = ConnectionRoute.Direct(profile.address, profile.port),
                    recordedAt = System.currentTimeMillis(),
                )
            )

            waitUntil {
                callFactory.requestCount >= 2 &&
                    env.apiClient.baseUrl == "http://${profile.address}:${profile.port}"
            }

            assertThat(history.drop(1)).doesNotContain(ConnectionStatus.DISCONNECTED)
            assertThat(env.manager.connectionStatus.value).isNotEqualTo(ConnectionStatus.DISCONNECTED)
        } finally {
            collectorJob.cancel()
        }
    }

    private fun createEnvironment(
        directBridgeReachable: Boolean = true,
        directBridgeInstanceId: String = "iid-direct",
        syncSseCallFactory: RecordingCancellationCallFactory = RecordingCancellationCallFactory(),
    ): TestEnvironment {
        val profileRepository = FakeServerProfileRepository()
        val sseEventRepository = FakeSseEventRepository()
        val sessionRepository = FakeSessionRepository()
        val databaseProvider = ActiveDatabaseProvider(RuntimeEnvironment.getApplication(), DatabaseFactory(RuntimeEnvironment.getApplication()))
        val gatewayInterceptor = GatewayInterceptor()
        val routeEvidenceReporter = RouteEvidenceReporter()
        val apiClient = OpencodeApiClient(
            OkHttpClient.Builder()
                .addInterceptor(gatewayInterceptor)
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath == "/api/bridge/status") {
                        val isGatewayRequest = request.url.host == "gateway.clawmate.net"
                        if (!isGatewayRequest && !directBridgeReachable) {
                            throw java.io.IOException("direct bridge unreachable")
                        }
                        val body = """
                            {
                                "bridge": {
                                  "version": "1.0.0",
                                  "port": 4097,
                                  "auth_enabled": true,
                                  "instance_id": "$directBridgeInstanceId"
                                },
                              "opencode": {
                                "status": "online",
                                "version": "1.15.5",
                                "url": "http://127.0.0.1:4098",
                                "directory": "D:/openmate"
                              }
                            }
                        """.trimIndent()
                        return@addInterceptor Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }
                    chain.proceed(request)
                }
                .build(),
            gatewayInterceptor = gatewayInterceptor,
            routeEvidenceReporter = routeEvidenceReporter,
        )
        val tokenStore = TokenStore(RuntimeEnvironment.getApplication())
        val syncSseClient = SyncSseClient(
            client = syncSseCallFactory,
            tokenStore = tokenStore,
            logger = NoOpSyncSseLogger,
        )
        val syncSseHandler = SyncSseHandler(
            syncSseClient = syncSseClient,
            repository = FakeSessionMessageRepository(),
            logStore = SyncLogStore(),
        )
        val routeEvidenceAggregator = RouteEvidenceAggregator(clock = { System.currentTimeMillis() })
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
            routeEvidenceReporter = routeEvidenceReporter,
            routeEvidenceAggregator = routeEvidenceAggregator,
            appForegroundMonitor = FakeAppForegroundMonitor(),
            networkChangeMonitor = FakeNetworkChangeMonitor(),
            logStore = SyncLogStore(),
        )
        managers += manager
        return TestEnvironment(
            manager = manager,
            profileRepository = profileRepository,
            sseEventRepository = sseEventRepository,
            apiClient = apiClient,
            gatewayInterceptor = gatewayInterceptor,
            syncSseCallFactory = syncSseCallFactory,
            routeEvidenceAggregator = routeEvidenceAggregator,
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
        val syncSseCallFactory: RecordingCancellationCallFactory,
        val routeEvidenceAggregator: RouteEvidenceAggregator,
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

    private class FakeAppForegroundMonitor : AppForegroundMonitor {
        override val isForeground: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeNetworkChangeMonitor : NetworkChangeMonitor {
        override val events = emptyFlow<com.openmate.app.connection.NetworkChangeEvent>()
    }

    private open class RecordingCancellationCallFactory : Call.Factory {
        val requests = Collections.synchronizedList(mutableListOf<Request>())

        val requestCount: Int
            get() = requests.size

        override fun newCall(request: Request): Call {
            requests += request
            return ImmediateCancellationCall(request)
        }
    }

    private class FailingSseCallFactory : RecordingCancellationCallFactory() {
        override fun newCall(request: Request): Call {
            requests += request
            return object : Call {
                override fun request(): Request = request

                override fun execute(): Response {
                    throw IOException("boom")
                }

                override fun enqueue(responseCallback: Callback) = Unit

                override fun cancel() = Unit

                override fun isExecuted(): Boolean = true

                override fun isCanceled(): Boolean = false

                override fun timeout(): Timeout = Timeout.NONE

                override fun clone(): Call = this
            }
        }
    }

    private class BlockingSseCallFactory(
        private val initialBody: String = "",
    ) : RecordingCancellationCallFactory() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        private val nextId = AtomicInteger(0)
        private val _startedCount = AtomicInteger(0)
        private val _cancelCount = AtomicInteger(0)

        val startedCount: Int
            get() = _startedCount.get()

        val cancelCount: Int
            get() = _cancelCount.get()

        override fun newCall(request: Request): Call {
            requests += request
            val id = nextId.incrementAndGet()
            events += "new:$id"
            return BlockingSseCall(request, id, initialBody, _startedCount, _cancelCount, events)
        }
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

    private class BlockingSseCall(
        private val requestValue: Request,
        private val id: Int,
        initialBody: String,
        private val startedCount: AtomicInteger,
        private val cancelCount: AtomicInteger,
        private val events: MutableList<String>,
    ) : Call {
        @Volatile
        private var canceled = false

        private val responseBody = BlockingResponseBody(initialBody)

        override fun request(): Request = requestValue

        override fun execute(): Response {
            startedCount.incrementAndGet()
            events += "start:$id"
            return Response.Builder()
                .request(requestValue)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build()
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() {
            canceled = true
            cancelCount.incrementAndGet()
            events += "cancel:$id"
            responseBody.finish()
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = BlockingSseCall(
            requestValue = requestValue,
            id = id,
            initialBody = "",
            startedCount = startedCount,
            cancelCount = cancelCount,
            events = events,
        )
    }

    private class BlockingResponseBody(initialBody: String) : ResponseBody() {
        private val input = PipedInputStream()
        private val output = PipedOutputStream(input)

        @Volatile
        private var finished = false

        init {
            thread(start = true, isDaemon = true) {
                if (initialBody.isNotEmpty()) {
                    output.write(initialBody.toByteArray())
                    output.flush()
                }
                while (!finished) {
                    Thread.sleep(10)
                }
                output.close()
            }
        }

        override fun contentType() = "text/event-stream".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source() = input.source().buffer()

        fun finish() {
            finished = true
        }

        override fun close() {
            finish()
            input.close()
            super.close()
        }
    }

    private object NoOpSyncSseLogger : SyncSseLogger {
        override fun logConnectStart(traceId: String, hasToken: Boolean) = Unit

        override fun logConnectSuccess(traceId: String, costMs: Long) = Unit

        override fun logDisconnected(traceId: String?, currentBaseUrl: String?) = Unit

        override fun logConnectFailure(traceId: String, error: Throwable) = Unit

        override fun logStreamClosed(traceId: String) = Unit

        override fun logNotification(event: BridgeEvent, traceId: String) = Unit
    }
}
