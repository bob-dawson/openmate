package com.openmate.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.openmate.app.connection.AppForegroundMonitor
import com.openmate.app.connection.NetworkChangeEvent
import com.openmate.app.connection.NetworkChangeMonitor
import com.openmate.app.connection.v2.ConnEffect
import com.openmate.app.connection.v2.ConnEvent
import com.openmate.app.connection.v2.ConnState
import com.openmate.app.connection.v2.ConnectionActor
import com.openmate.app.connection.v2.EffectExecutor
import com.openmate.app.connection.v2.Route
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ConnectionSnapshot
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseSignal
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    private val gatewayInterceptor: GatewayInterceptor,
    private val appForegroundMonitor: AppForegroundMonitor,
    private val networkChangeMonitor: NetworkChangeMonitor,
    private val logStore: SyncLogStore,
    private val permissionRepository: PermissionRepository,
    private val questionRepository: QuestionRepository,
) : ConnectionRepository {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val GATEWAY_URL = "https://gateway.clawmate.net"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionSnapshot = MutableStateFlow<ConnectionSnapshot?>(null)
    override val connectionSnapshot: StateFlow<ConnectionSnapshot?> = _connectionSnapshot.asStateFlow()

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    override val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _needsRepairing = MutableStateFlow<String?>(null)
    override val needsRepairing: StateFlow<String?> = _needsRepairing.asStateFlow()

    val client: OpencodeApiClient get() = apiClient

    private val prevState = java.util.concurrent.atomic.AtomicReference<ConnState>(ConnState.Idle())

    @Volatile
    private var lastEvent: ConnEvent? = null

    private val sendEvent: (ConnEvent) -> Unit = { event ->
        lastEvent = event
        scope.launch { actor.processEvent(event) }
    }

    private val executor = EffectExecutor(
        scope = scope,
        sendEvent = sendEvent,
        apiClient = apiClient,
        gatewayInterceptor = gatewayInterceptor,
        syncSseClient = syncSseClient,
        syncSseHandler = syncSseHandler,
        sseEventRepository = sseEventRepository,
        sessionRepository = sessionRepository,
        profileRepository = profileRepository,
        tokenStore = tokenStore,
        logStore = logStore,
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
    )

    private val actor = ConnectionActor { effect: ConnEffect ->
        executor.execute(effect)
    }

    @Volatile
    private var restoreStarted = false

    @Volatile
    private var disconnectRequested = false

    @Volatile
    private var runtimeMonitoringStarted = false

    init {
        scope.launch {
            actor.state.collect { state -> applyState(state) }
        }
        scope.launch {
            syncSseClient.transportSignals.collect { signal ->
                handleTransportSignal(signal)
            }
        }
        scope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "SSE状态变化 status=$status activeProfile=${_activeProfile.value?.id}")
            }
        }
    }

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

    fun startRuntimeMonitoring() {
        if (runtimeMonitoringStarted) return
        runtimeMonitoringStarted = true
        scope.launch {
            appForegroundMonitor.isForeground.collect { isForeground ->
                if (isForeground) {
                    sendEvent(ConnEvent.AppForegrounded)
                    val s = actor.state.value
                    if (s is ConnState.Idle && s.profile != null) {
                        connect(s.profile)
                    }
                }
            }
        }
        scope.launch {
            networkChangeMonitor.events.collect { event ->
                when (event) {
                    NetworkChangeEvent.Available -> {
                        sendEvent(ConnEvent.NetworkAvailable)
                        syncSseHandler.requestCatchUpSync()
                    }
                    NetworkChangeEvent.Lost -> {
                        sendEvent(ConnEvent.NetworkLost)
                    }
                    NetworkChangeEvent.PathChanged -> {
                        sendEvent(ConnEvent.NetworkAvailable)
                    }
                }
            }
        }
    }

    override fun connect(profile: ServerProfile) {
        val current = _activeProfile.value
        val sameProfile = current?.id == profile.id
        val currentStatus = _connectionStatus.value
        val alreadyActive = sameProfile && currentStatus in setOf(
            ConnectionStatus.CONNECTING,
            ConnectionStatus.CONNECTED,
            ConnectionStatus.GATEWAY_CONNECTED,
        )
        if (alreadyActive) return

        syncSseHandler.stop()
        permissionRepository.clearPending()
        questionRepository.clearPending()

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _isConnected.value = false
        _errorMessage.value = null
        _needsRepairing.value = null
        _activeProfile.value = profile

        scope.launch {
            tokenStore.setActiveProfileId(profile.id)
            dbProvider.setActive(profile.id)

            val directUrl = "http://${profile.address}:${profile.port}"
            if (profile.instanceId.isNotBlank()) {
                apiClient.baseUrl = directUrl
                gatewayInterceptor.instanceId = profile.instanceId
            } else {
                apiClient.baseUrl = directUrl
                gatewayInterceptor.instanceId = null
            }

            val prevState = actor.state.value
            if (prevState is ConnState.Idle || prevState is ConnState.Failed || prevState is ConnState.NeedsRepair) {
                actor.processEvent(ConnEvent.Connect(profile))
            } else {
                actor.processEvent(ConnEvent.Disconnect)
                actor.processEvent(ConnEvent.Connect(profile))
            }
        }

        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "开始连接 id=${profile.id} iid='${profile.instanceId}'")
    }

    override fun reconnect() {
        val profile = _activeProfile.value ?: return
        Log.i(TAG, "reconnect: instance=${profile.instanceId}")
        scope.launch { actor.processEvent(ConnEvent.Retry) }
    }

    override fun disconnect() {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, "断开连接")
        disconnectRequested = true
        scope.launch { actor.processEvent(ConnEvent.Disconnect) }
    }

    override fun confirmRepairing(profileId: String, token: String) {
        scope.launch {
            tokenStore.saveToken(profileId, token)
            _needsRepairing.value = null
            actor.processEvent(ConnEvent.RepairCompleted(profileId))
            _activeProfile.value?.let { connect(it) }
        }
    }

    override fun notifyProfileUpdated(profile: ServerProfile) {
        actor.updateProfile(profile)
        _activeProfile.value = profile
    }

    override fun clearNeedsRepairing() {
        _needsRepairing.value = null
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    private fun applyState(state: ConnState) {
        val prev = prevState.getAndSet(state)
        val evt = lastEvent
        if (evt != null && prev != state) {
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "[${prev.name}] ${evt::class.simpleName} -> [${state.name}]")
        }
        lastEvent = null
        val status = state.toLegacyStatus()
        _connectionStatus.value = status
        _isConnected.value = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.GATEWAY_CONNECTED

        val phase = state.toPhase()
        val activeRoute = state.toConnectionRoute()
        _connectionSnapshot.value = ConnectionSnapshot(
            phase = phase,
            activeRoute = activeRoute,
            desiredProfileId = state.profile?.id,
            isUsable = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.GATEWAY_CONNECTED,
            needsUserRepair = state is ConnState.NeedsRepair,
            message = _errorMessage.value,
        )

        when (state) {
            is ConnState.Failed -> _errorMessage.value = state.reason
            is ConnState.NeedsRepair -> _needsRepairing.value = state.profile.id
            is ConnState.Idle -> {
                if (disconnectRequested) {
                    disconnectRequested = false
                    scope.launch { clearConnection() }
                }
            }
            else -> Unit
        }
    }

    private fun handleTransportSignal(signal: SyncSseSignal) {
        val route = routeForBaseUrl(signal.routeBaseUrl)
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "SSE信号 signal=${signal::class.simpleName} url=${signal.routeBaseUrl} route=$route")
        if (route == null) return
        when (signal) {
            is SyncSseSignal.ConnectStarted -> Unit
            is SyncSseSignal.Connected -> sendEvent(ConnEvent.SseConnected(route))
            is SyncSseSignal.EventReceived -> Unit
            is SyncSseSignal.StreamClosed -> sendEvent(ConnEvent.SseStreamClosed(route))
            is SyncSseSignal.Failed -> sendEvent(ConnEvent.SseFailed(route, signal.message))
        }
    }

    private fun routeForBaseUrl(baseUrl: String): Route? {
        val profile = _activeProfile.value ?: return null
        return if (baseUrl == GATEWAY_URL && profile.instanceId.isNotBlank()) {
            Route.Gateway(profile.instanceId)
        } else {
            Route.Direct(profile.address, profile.port)
        }
    }

    private suspend fun clearConnection() {
        syncSseHandler.stop()
        permissionRepository.clearPending()
        questionRepository.clearPending()
        apiClient.baseUrl = ""
        gatewayInterceptor.instanceId = null
        dbProvider.clearActive()
        tokenStore.setActiveProfileId(null)
        _activeProfile.value = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    private fun ConnState.toLegacyStatus(): ConnectionStatus = when (this) {
        is ConnState.Idle -> ConnectionStatus.DISCONNECTED
        is ConnState.ProbingNetwork -> ConnectionStatus.CONNECTING
        is ConnState.WaitingForNetwork -> ConnectionStatus.DISCONNECTED
        is ConnState.ProbingDirect -> ConnectionStatus.CONNECTING
        is ConnState.ProbingGateway -> ConnectionStatus.CONNECTING
        is ConnState.Connecting -> ConnectionStatus.CONNECTING
        is ConnState.Connected -> when (route) {
            is Route.Gateway -> ConnectionStatus.GATEWAY_CONNECTED
            else -> ConnectionStatus.CONNECTED
        }
        is ConnState.Recovering -> ConnectionStatus.CONNECTING
        is ConnState.Failed -> ConnectionStatus.ERROR
        is ConnState.NeedsRepair -> ConnectionStatus.PAIRING
    }

    private fun ConnState.toPhase(): ConnectionPhase = when (this) {
        is ConnState.Idle -> ConnectionPhase.DISCONNECTED
        is ConnState.ProbingNetwork -> ConnectionPhase.EVALUATING
        is ConnState.WaitingForNetwork -> ConnectionPhase.DISCONNECTED
        is ConnState.ProbingDirect -> ConnectionPhase.EVALUATING
        is ConnState.ProbingGateway -> ConnectionPhase.EVALUATING
        is ConnState.Connecting -> ConnectionPhase.CONNECTING
        is ConnState.Connected -> ConnectionPhase.CONNECTED
        is ConnState.Recovering -> ConnectionPhase.RECOVERING
        is ConnState.Failed -> ConnectionPhase.FAILED
        is ConnState.NeedsRepair -> ConnectionPhase.NEEDS_REPAIR
    }

    private fun ConnState.toConnectionRoute(): ConnectionRoute? = when (this) {
        is ConnState.Connecting -> route.toConnectionRoute()
        is ConnState.Connected -> route.toConnectionRoute()
        else -> null
    }

    private fun Route.toConnectionRoute(): ConnectionRoute = when (this) {
        is Route.Direct -> ConnectionRoute.Direct(address, port)
        is Route.Gateway -> ConnectionRoute.Gateway(instanceId)
    }

    private val ConnState.profile: ServerProfile? get() = when (this) {
        is ConnState.Idle -> profile
        is ConnState.ProbingNetwork -> profile
        is ConnState.WaitingForNetwork -> profile
        is ConnState.ProbingDirect -> profile
        is ConnState.ProbingGateway -> profile
        is ConnState.Connecting -> profile
        is ConnState.Connected -> profile
        is ConnState.Recovering -> profile
        is ConnState.Failed -> profile
        is ConnState.NeedsRepair -> profile
    }
}