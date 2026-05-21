package com.openmate.app

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    val client: OpencodeApiClient get() = apiClient

    init {
        scope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                if (_activeProfile.value == null) {
                    return@collect
                }
                val mapped = if (status == ConnectionStatus.CONNECTED && useGateway) {
                    ConnectionStatus.GATEWAY_CONNECTED
                } else {
                    status
                }
                _connectionStatus.value = mapped
                _isConnected.value = mapped == ConnectionStatus.CONNECTED || mapped == ConnectionStatus.GATEWAY_CONNECTED
                if (mapped == ConnectionStatus.CONNECTED || mapped == ConnectionStatus.GATEWAY_CONNECTED) {
                    sessionRepository.refreshSessionStatuses()
                }
                if (status == ConnectionStatus.ERROR && !useGateway) {
                    _errorMessage.value = "Connection lost"
                    val profile = _activeProfile.value
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "SSE断开", message = "profile=${profile?.name} instanceId='${profile?.instanceId}' useGateway=$useGateway")
                    if (profile != null && profile.instanceId.isNotEmpty()) {
                        logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "直连断开", message = "尝试网关回退")
                        attemptGatewayFallback(profile)
                    }
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
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return
                    }
                }
                useGateway = false
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "直连成功", message = directUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Direct connect failed: ${e.message}")
                logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "直连失败", message = e.message ?: "")
                if (hasIid) {
                    useGateway = true
                    apiClient.baseUrl = GATEWAY_URL
                    gatewayInterceptor.instanceId = profile.instanceId
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "回退网关", message = "instance=${profile.instanceId}")
                } else {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    _errorMessage.value = "Bridge not reachable: ${e.message}"
                    return
                }
            }
        } else {
            apiClient.baseUrl = GATEWAY_URL
            gatewayInterceptor.instanceId = profile.instanceId
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
        Log.i(TAG, "switching to gateway")
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "切换网关", message = "instance=${profile.instanceId}")

        startSyncSse()
    }

    private fun startSseConnections(profile: ServerProfile) {
        syncSseHandler.start()
        startSyncSse()

        if (useGateway) {
            startDirectCheckLoop()
        }
    }

    private fun startSyncSse() {
        syncSseJob?.cancel()
        val profile = _activeProfile.value ?: return
        syncSseClient.instanceId = if (useGateway) profile.instanceId else null
        syncSseJob = scope.launch {
            syncSseClient.connect(apiClient.baseUrl)
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

        startSyncSse()
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
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "断开连接", message = "")
        teardownCurrentConnection(clearActiveProfile = false)
        scope.launch { clearConnection() }
    }

    private suspend fun clearConnection() {
        dbProvider.clearActive()
        _activeProfile.value = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        tokenStore.setActiveProfileId(null)
    }
}
