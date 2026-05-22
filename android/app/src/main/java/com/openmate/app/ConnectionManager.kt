package com.openmate.app

import android.util.Log
import com.openmate.app.connection.AppForegroundMonitor
import com.openmate.app.connection.ConnectionAction
import com.openmate.app.connection.ConnectionEvent
import com.openmate.app.connection.ConnectionMachineState
import com.openmate.app.connection.ConnectionReducer
import com.openmate.app.connection.NetworkChangeEvent
import com.openmate.app.connection.NetworkChangeMonitor
import com.openmate.app.connection.RouteEvidence
import com.openmate.app.connection.RouteEvidenceAggregator
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
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.ApiRouteResult
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.RouteEvidenceReporter
import com.openmate.core.network.SyncSseSignal
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    private val gatewayInterceptor: GatewayInterceptor,
    private val routeEvidenceReporter: RouteEvidenceReporter,
    private val routeEvidenceAggregator: RouteEvidenceAggregator,
    private val appForegroundMonitor: AppForegroundMonitor,
    private val networkChangeMonitor: NetworkChangeMonitor,
    private val logStore: SyncLogStore,
) : ConnectionRepository {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val GATEWAY_URL = "https://gateway.clawmate.net"
        private const val DIRECT_CHECK_INTERVAL_MS = 30_000L
        private const val DIRECT_CHECK_TIMEOUT_MS = 5_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncSseJob: Job? = null
    private var directCheckJob: Job? = null
    private val connectRequestId = AtomicLong(0)

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

    @Volatile
    private var useGateway = false

    @Volatile
    private var activeConnectRequestId = 0L

    @Volatile
    private var pendingProfileId: String? = null

    @Volatile
    private var restoreStarted = false

    @Volatile
    private var runtimeMonitoringStarted = false

    @Volatile
    private var lastHandledRouteRevision = -1L

    private val machineState = MutableStateFlow(
        ConnectionMachineState(
            desiredProfileId = null,
            activeRoute = null,
            phase = ConnectionPhase.DISCONNECTED,
            recoveryGeneration = 0L,
        )
    )

    val client: OpencodeApiClient get() = apiClient

    init {
        applyMachineState(machineState.value)
        scope.launch {
            routeEvidenceReporter.events.collect { result ->
                when (result) {
                    is ApiRouteResult.Success -> {
                        routeEvidenceAggregator.record(
                            RouteEvidence.ApiSuccess(
                                route = result.route,
                                recordedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    is ApiRouteResult.NetworkFailure -> {
                        routeEvidenceAggregator.record(
                            RouteEvidence.ApiNetworkFailure(
                                route = result.route,
                                recordedAt = System.currentTimeMillis(),
                                message = result.message,
                            )
                        )
                    }
                }
            }
        }
        scope.launch {
            syncSseClient.transportSignals.collect { signal ->
                handleTransportSignal(signal)
            }
        }
        scope.launch {
            routeEvidenceAggregator.snapshot.collect { snapshot ->
                if (snapshot.revision == lastHandledRouteRevision) {
                    return@collect
                }
                lastHandledRouteRevision = snapshot.revision
                dispatch(ConnectionEvent.RouteHealthUpdated(snapshot.revision))
                handleRouteHealthSnapshot(snapshot)
            }
        }
        scope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                if (_activeProfile.value == null) {
                    return@collect
                }
                val route = if (useGateway) {
                    _activeProfile.value?.instanceId?.takeIf { it.isNotEmpty() }?.let { ConnectionRoute.Gateway(it) }
                } else {
                    _activeProfile.value?.let { ConnectionRoute.Direct(it.address, it.port) }
                }
                when (status) {
                    ConnectionStatus.CONNECTED, ConnectionStatus.GATEWAY_CONNECTED -> {
                        if (route != null) {
                            dispatch(ConnectionEvent.SseConnected(route))
                        }
                    }
                    ConnectionStatus.ERROR -> {
                        if (route != null) {
                            dispatch(ConnectionEvent.SseFailed(route, "sse status error"))
                        }
                    }
                    else -> Unit
                }
                val mapped = machineState.value.toLegacyStatus()
                _connectionStatus.value = mapped
                _isConnected.value = mapped == ConnectionStatus.CONNECTED || mapped == ConnectionStatus.GATEWAY_CONNECTED
                if (mapped == ConnectionStatus.CONNECTED || mapped == ConnectionStatus.GATEWAY_CONNECTED) {
                    sessionRepository.refreshSessionStatuses()
                }
                if (status == ConnectionStatus.ERROR && !useGateway) {
                    _errorMessage.value = "Connection lost"
                    val profile = _activeProfile.value
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "SSE断开", message = "profile=${profile?.name} instanceId='${profile?.instanceId}' useGateway=$useGateway")
                }
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
                    dispatch(ConnectionEvent.AppForegrounded)
                } else {
                    dispatch(ConnectionEvent.AppBackgrounded)
                }
            }
        }
        scope.launch {
            networkChangeMonitor.events.collect { event ->
                when (event) {
                    NetworkChangeEvent.Available -> {
                        dispatch(ConnectionEvent.NetworkAvailable)
                        onRecoveredConnectivity()
                    }
                    NetworkChangeEvent.Lost -> dispatch(ConnectionEvent.NetworkLost)
                    NetworkChangeEvent.PathChanged -> dispatch(ConnectionEvent.NetworkPathChanged)
                }
            }
        }
    }

    override fun connect(profile: ServerProfile) {
        val currentProfile = _activeProfile.value
        val currentStatus = _connectionStatus.value
        val sameProfile = currentProfile?.id == profile.id
        val alreadyActive = sameProfile && currentStatus in setOf(
            ConnectionStatus.CONNECTING,
            ConnectionStatus.CONNECTED,
            ConnectionStatus.GATEWAY_CONNECTED,
        )
        if (alreadyActive || pendingProfileId == profile.id) {
            return
        }

        val requestId = connectRequestId.incrementAndGet()
        activeConnectRequestId = requestId
        pendingProfileId = profile.id
        dispatch(ConnectionEvent.UserConnect(profile))

        scope.launch {
            try {
                val previousProfile = currentProfile
                if (previousProfile?.id != null && previousProfile.id != profile.id) {
                    teardownCurrentConnection(clearActiveProfile = false)
                }
                startConnection(profile, requestId)
            } finally {
                if (isCurrentRequest(requestId)) {
                    pendingProfileId = null
                }
            }
        }
    }

    override fun reconnect() {
        val profile = _activeProfile.value ?: return
        Log.i(TAG, "reconnect: instance=${profile.instanceId}")
        dispatch(ConnectionEvent.UserRetry)
        connect(profile)
    }

    private fun isCurrentRequest(requestId: Long): Boolean = activeConnectRequestId == requestId

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

    private suspend fun startConnection(profile: ServerProfile, requestId: Long) {
        if (!isCurrentRequest(requestId)) {
            return
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _isConnected.value = false
        _errorMessage.value = null
        _needsRepairing.value = null
        _activeProfile.value = profile
        machineState.value = machineState.value.copy(desiredProfileId = profile.id)
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "开始连接", message = "id=${profile.id} instanceId='${profile.instanceId}' len=${profile.instanceId.length}")

        tokenStore.setActiveProfileId(profile.id)
        dbProvider.setActive(profile.id)

        val hasIid = profile.instanceId.isNotEmpty()
        if (!useGateway) {
            val directUrl = "http://${profile.address}:${profile.port}"
            apiClient.baseUrl = directUrl
            gatewayInterceptor.instanceId = null

            try {
                val status = apiClient.bridgeStatus()
                if (status.bridge.version.isBlank()) {
                    _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
                    _errorMessage.value = "Not a Bridge server."
                    return
                }
                val iid = status.bridge.instanceId
                if (iid.isNotEmpty() && iid != profile.instanceId) {
                    val updated = profile.copy(instanceId = iid)
                    profileRepository.save(updated)
                    _activeProfile.value = updated
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "更新 instanceId", message = "old='${profile.instanceId}' new='$iid'")
                }
                if (status.bridge.authEnabled) {
                    val token = tokenStore.getToken(profile.id)
                    if (token == null) {
                        _needsRepairing.value = profile.id
                        machineState.value = machineState.value.copy(phase = ConnectionPhase.NEEDS_REPAIR)
                        applyMachineState(machineState.value)
                        return
                    }
                }
                useGateway = false
                machineState.value = machineState.value.copy(
                    activeRoute = ConnectionRoute.Direct(profile.address, profile.port),
                    phase = ConnectionPhase.CONNECTING,
                )
                applyMachineState(machineState.value)
                routeEvidenceAggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Direct(profile.address, profile.port), System.currentTimeMillis()))
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "直连成功", message = directUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Direct connect failed: ${e.message}")
                logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "直连失败", message = e.message ?: "")
                routeEvidenceAggregator.record(RouteEvidence.ProbeFailure(ConnectionRoute.Direct(profile.address, profile.port), System.currentTimeMillis(), e.message))
                if (hasIid) {
                    useGateway = true
                    apiClient.baseUrl = GATEWAY_URL
                    gatewayInterceptor.instanceId = profile.instanceId
                    machineState.value = machineState.value.copy(
                        activeRoute = ConnectionRoute.Gateway(profile.instanceId),
                        phase = ConnectionPhase.CONNECTING,
                    )
                    applyMachineState(machineState.value)
                    routeEvidenceAggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Gateway(profile.instanceId), System.currentTimeMillis()))
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "回退网关", message = "instance=${profile.instanceId}")
                } else {
                    machineState.value = machineState.value.copy(phase = ConnectionPhase.FAILED)
                    applyMachineState(machineState.value)
                    _errorMessage.value = "Bridge not reachable: ${e.message}"
                    return
                }
            }
        } else {
            apiClient.baseUrl = GATEWAY_URL
            gatewayInterceptor.instanceId = profile.instanceId
            machineState.value = machineState.value.copy(
                activeRoute = ConnectionRoute.Gateway(profile.instanceId),
                phase = ConnectionPhase.CONNECTING,
            )
            applyMachineState(machineState.value)
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "跳过直连", message = "已在网关模式, instance=${profile.instanceId}")
        }

        if (!isCurrentRequest(requestId)) {
            return
        }

        startSseConnections(profile)
        val savedProfile = _activeProfile.value ?: profile
        profileRepository.save(savedProfile.copy(lastConnectedAt = System.currentTimeMillis()))
    }

    private suspend fun attemptGatewayFallback(profile: ServerProfile) {
        Log.d(TAG, "attemptGatewayFallback: instance=${profile.instanceId}")
        if (!isGatewayOnline(profile.instanceId)) {
            Log.w(TAG, "gateway not online")
            logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "网关不可用", message = "instance=${profile.instanceId}")
            return
        }
        useGateway = true
        apiClient.baseUrl = GATEWAY_URL
        gatewayInterceptor.instanceId = profile.instanceId
        routeEvidenceAggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Gateway(profile.instanceId), System.currentTimeMillis()))
        Log.i(TAG, "switching to gateway")
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "切换网关", message = "instance=${profile.instanceId}")

        startSyncSse(forceRestart = true)
    }

    private fun startSseConnections(profile: ServerProfile) {
        syncSseHandler.start()
        startSyncSse(forceRestart = true)

        if (useGateway) {
            startDirectCheckLoop()
        }
    }

    private fun startSyncSse(forceRestart: Boolean = false) {
        syncSseJob?.cancel()
        val profile = _activeProfile.value ?: return
        syncSseClient.instanceId = if (useGateway) profile.instanceId else null
        syncSseJob = scope.launch {
            syncSseClient.connect(apiClient.baseUrl, forceRestart = forceRestart)
        }
    }

    private fun startDirectCheckLoop() {
        stopDirectCheckLoop()
        val profile = _activeProfile.value ?: return
        directCheckJob = scope.launch {
            while (useGateway) {
                delay(DIRECT_CHECK_INTERVAL_MS)
                if (!useGateway) break
                if (isDirectReachable(profile.address, profile.port)) {
                    Log.i(TAG, "direct check: direct reachable, switching back")
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "直连恢复", message = "切回直连")
                    switchBackToDirect(profile)
                    break
                } else {
                    Log.d(TAG, "direct check: still unreachable")
                }
            }
        }
    }

    private fun stopDirectCheckLoop() {
        directCheckJob?.cancel()
        directCheckJob = null
    }

    private suspend fun switchBackToDirect(profile: ServerProfile) {
        useGateway = false
        apiClient.baseUrl = "http://${profile.address}:${profile.port}"
        gatewayInterceptor.instanceId = null
        stopDirectCheckLoop()

        routeEvidenceAggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Direct(profile.address, profile.port), System.currentTimeMillis()))
        startSyncSse(forceRestart = true)
        profileRepository.save(profile.copy(lastConnectedAt = System.currentTimeMillis()))
    }

    private fun isGatewayOnline(instanceId: String): Boolean {
        return try {
            val url = URL("$GATEWAY_URL/api/gateway/status?instance_id=$instanceId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = DIRECT_CHECK_TIMEOUT_MS
            conn.readTimeout = DIRECT_CHECK_TIMEOUT_MS
            val code = conn.responseCode
            conn.disconnect()
            val success = code == 200
            Log.d(TAG, "gateway status: code=$code online=$success")
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "网关状态", message = "instance=$instanceId code=$code online=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "gateway check failed", e)
            logStore.log(SyncLogLevel.Error, SyncLogCategory.Gateway, title = "网关检查失败", message = "${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun isDirectReachable(address: String, port: Int): Boolean {
        return try {
            val url = URL("http://$address:$port/api/bridge/status")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = DIRECT_CHECK_TIMEOUT_MS
            conn.readTimeout = DIRECT_CHECK_TIMEOUT_MS
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    override fun confirmRepairing(profileId: String, token: String) {
        scope.launch {
            tokenStore.saveToken(profileId, token)
            _needsRepairing.value = null
            dispatch(ConnectionEvent.RepairCompleted(profileId))
            _activeProfile.value?.let { connect(it) }
        }
    }

    override fun clearNeedsRepairing() {
        _needsRepairing.value = null
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    override fun disconnect() {
        activeConnectRequestId = 0L
        pendingProfileId = null
        dispatch(ConnectionEvent.UserDisconnect)
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "断开连接", message = "")
        teardownCurrentConnection(clearActiveProfile = false)
        scope.launch { clearConnection() }
    }

    internal fun forceMachineStateForTest(state: ConnectionMachineState) {
        machineState.value = state
        applyMachineState(state)
    }

    private fun dispatch(event: ConnectionEvent) {
        val result = ConnectionReducer.reduce(machineState.value, event)
        machineState.value = result.nextState
        applyMachineState(result.nextState)
        result.actions.forEach { execute(it) }
    }

    private fun execute(action: ConnectionAction) {
        when (action) {
            ConnectionAction.ReevaluateRoutes -> Unit
            ConnectionAction.StartBackoff -> Unit
            ConnectionAction.StopActiveTransport -> {
                sseEventRepository.disconnect()
                syncSseJob?.cancel()
                syncSseClient.disconnect()
            }
            ConnectionAction.RefreshSessionStatuses -> {
                scope.launch {
                    sessionRepository.refreshSessionStatuses()
                }
            }
        }
    }

    private fun applyMachineState(state: ConnectionMachineState) {
        _connectionStatus.value = state.toLegacyStatus()
        _connectionSnapshot.value = state.toSnapshot()
        _isConnected.value = _connectionStatus.value == ConnectionStatus.CONNECTED ||
            _connectionStatus.value == ConnectionStatus.GATEWAY_CONNECTED
    }

    private fun handleTransportSignal(signal: SyncSseSignal) {
        val route = routeForBaseUrl(signal.routeBaseUrl) ?: return
        when (signal) {
            is SyncSseSignal.ConnectStarted -> Unit
            is SyncSseSignal.Connected -> dispatch(ConnectionEvent.SseConnected(route))
            is SyncSseSignal.EventReceived -> {
                routeEvidenceAggregator.record(RouteEvidence.SsePositive(route, System.currentTimeMillis()))
                dispatch(ConnectionEvent.SseEventReceived(route))
            }
            is SyncSseSignal.StreamClosed -> {
                routeEvidenceAggregator.record(RouteEvidence.SseSuspicion(route, System.currentTimeMillis(), "stream closed"))
                dispatch(ConnectionEvent.SseStreamClosed(route))
            }
            is SyncSseSignal.Failed -> {
                routeEvidenceAggregator.record(RouteEvidence.SseSuspicion(route, System.currentTimeMillis(), signal.message))
                dispatch(ConnectionEvent.SseFailed(route, signal.message))
            }
        }
    }

    private fun handleRouteHealthSnapshot(snapshot: com.openmate.app.connection.RouteHealthSnapshot) {
        val preferredRoute = selectPreferredRoute(snapshot) ?: return
        if (machineState.value.activeRoute == preferredRoute && machineState.value.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED)) {
            return
        }
        handoffToRoute(preferredRoute)
    }

    private fun selectPreferredRoute(snapshot: com.openmate.app.connection.RouteHealthSnapshot): ConnectionRoute? {
        val profile = _activeProfile.value ?: return null
        return when {
            snapshot.direct.isUsable -> ConnectionRoute.Direct(profile.address, profile.port)
            snapshot.gateway.isUsable && profile.instanceId.isNotBlank() -> ConnectionRoute.Gateway(profile.instanceId)
            else -> null
        }
    }

    private fun handoffToRoute(route: ConnectionRoute) {
        when (route) {
            is ConnectionRoute.Direct -> {
                useGateway = false
                apiClient.baseUrl = "http://${route.address}:${route.port}"
                gatewayInterceptor.instanceId = null
            }
            is ConnectionRoute.Gateway -> {
                useGateway = true
                apiClient.baseUrl = GATEWAY_URL
                gatewayInterceptor.instanceId = route.instanceId
            }
        }
        machineState.value = machineState.value.copy(activeRoute = route, phase = ConnectionPhase.CONNECTING)
        applyMachineState(machineState.value)
        startSyncSse(forceRestart = true)
    }

    private fun routeForBaseUrl(baseUrl: String): ConnectionRoute? {
        val profile = _activeProfile.value ?: return null
        val gatewayBaseUrl = GATEWAY_URL
        return if (baseUrl == gatewayBaseUrl && profile.instanceId.isNotBlank()) {
            ConnectionRoute.Gateway(profile.instanceId)
        } else {
            ConnectionRoute.Direct(profile.address, profile.port)
        }
    }

    private fun onRecoveredConnectivity() {
        syncSseHandler.requestCatchUpSync()
    }

    private fun ConnectionMachineState.toLegacyStatus(): ConnectionStatus {
        return when (phase) {
            ConnectionPhase.DISCONNECTED -> ConnectionStatus.DISCONNECTED
            ConnectionPhase.EVALUATING,
            ConnectionPhase.CONNECTING,
            ConnectionPhase.RECOVERING -> ConnectionStatus.CONNECTING
            ConnectionPhase.CONNECTED -> when (activeRoute) {
                is ConnectionRoute.Gateway -> ConnectionStatus.GATEWAY_CONNECTED
                else -> ConnectionStatus.CONNECTED
            }
            ConnectionPhase.NEEDS_REPAIR -> ConnectionStatus.PAIRING
            ConnectionPhase.FAILED -> ConnectionStatus.ERROR
        }
    }

    private fun ConnectionMachineState.toSnapshot(): ConnectionSnapshot {
        val legacyStatus = toLegacyStatus()
        return ConnectionSnapshot(
            phase = phase,
            activeRoute = activeRoute,
            desiredProfileId = desiredProfileId,
            isUsable = legacyStatus == ConnectionStatus.CONNECTED || legacyStatus == ConnectionStatus.GATEWAY_CONNECTED,
            needsUserRepair = phase == ConnectionPhase.NEEDS_REPAIR,
            message = _errorMessage.value,
        )
    }

    private suspend fun clearConnection() {
        dbProvider.clearActive()
        _activeProfile.value = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        tokenStore.setActiveProfileId(null)
    }
}
