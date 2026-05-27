package com.openmate.app.connection.v2

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.ActiveProfileProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openmate.core.network.dto.BridgeStatusResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class EffectExecutor(
    private val scope: CoroutineScope,
    private val sendEvent: (ConnEvent) -> Unit,
    private val apiClient: OpencodeApiClient,
    private val activeProfileProvider: ActiveProfileProvider,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val profileRepository: ServerProfileRepository,
    private val tokenStore: TokenStore,
    private val logStore: SyncLogStore,
    private val connectivityManager: ConnectivityManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var backoffJob: Job? = null
    private var sseJob: Job? = null
    private var directCheckJob: Job? = null

    private fun activeProfile(): ServerProfile? = activeProfileProvider.getActiveProfile()

    fun execute(effect: ConnEffect) {
        when (effect) {
            is ConnEffect.CheckNetwork -> checkNetwork()
            is ConnEffect.ProbeGateway -> probeGateway()
            is ConnEffect.ProbeDirect -> probeDirect()
            is ConnEffect.StartSse -> startSse()
            is ConnEffect.StopSse -> stopSse()
            is ConnEffect.StartBackoff -> startBackoff(effect.delayMs)
            is ConnEffect.StopBackoff -> stopBackoff()
            is ConnEffect.RefreshSessions -> scope.launch { sessionRepository.refreshSessionStatuses() }
            is ConnEffect.UpdateLastConnectedAt -> scope.launch {
                val profile = profileRepository.getById(effect.profileId)
                if (profile != null) {
                    profileRepository.save(profile.copy(lastConnectedAt = System.currentTimeMillis()))
                }
            }
            is ConnEffect.StartDirectCheckLoop -> startDirectCheckLoop()
            is ConnEffect.StopDirectCheckLoop -> stopDirectCheckLoop()
            is ConnEffect.RestartDirectCheckLoop -> { stopDirectCheckLoop(); startDirectCheckLoop() }
        }
    }

    private fun checkNetwork() {
        scope.launch {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val hasNetwork = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网络检测 hasNetwork=$hasNetwork isWifi=$isWifi")
            when {
                !hasNetwork -> sendEvent(ConnEvent.NetworkIsNone)
                isWifi -> sendEvent(ConnEvent.NetworkIsWifi)
                else -> sendEvent(ConnEvent.NetworkIsMobile)
            }
        }
    }

    private fun probeGateway() {
        val instanceId = activeProfile()?.instanceId ?: return
        scope.launch {
            val success = isGatewayOnline(instanceId)
            if (success) {
                sendEvent(ConnEvent.ProbeOk(Route.Gateway(instanceId)))
            } else {
                sendEvent(ConnEvent.ProbeFail(Route.Gateway(instanceId)))
            }
        }
    }

    private fun probeDirect() {
        val profile = activeProfile() ?: return
        val address = profile.address
        val port = profile.port
        scope.launch {
            try {
                val status = fetchBridgeStatus(address, port)
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "直连探测 address=$address:$port ok=true version=${status.bridge.version}")
                if (status.bridge.version.isBlank()) {
                    sendEvent(ConnEvent.BridgeNotBridge(profileRepository.getAll().first { it.address == address && it.port == port }))
                    return@launch
                }
                if (status.bridge.authEnabled) {
                    val p = profileRepository.getAll().first { it.address == address && it.port == port }
                    val token = tokenStore.getToken(p.id)
                    if (token == null) {
                        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "需要配对 profile=${p.id}")
                        sendEvent(ConnEvent.BridgeNeedsRepair(p))
                        return@launch
                    }
                }
                if (status.bridge.instanceId.isNotEmpty()) {
                    val p = profileRepository.getAll().firstOrNull { it.address == address && it.port == port }
                    if (p != null && status.bridge.instanceId != p.instanceId) {
                        val updated = p.copy(instanceId = status.bridge.instanceId)
                        profileRepository.save(updated)
                        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "更新instanceId old='${p.instanceId}' new='${status.bridge.instanceId}'")
                    }
                }
                sendEvent(ConnEvent.ProbeOk(Route.Direct(address, port)))
            } catch (e: Exception) {
                logStore.log(SyncLogLevel.Warn, SyncLogCategory.Connection, "直连探测失败 address=$address:$port ${e.javaClass.simpleName}: ${e.message}")
                sendEvent(ConnEvent.ProbeFail(Route.Direct(address, port)))
            }
        }
    }

    private suspend fun fetchBridgeStatus(address: String, port: Int): BridgeStatusResponse {
        val request = Request.Builder()
            .url("http://$address:$port/api/bridge/status")
            .get()
            .build()
        val response = probeClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw IllegalStateException("Empty body")
        return json.decodeFromString<BridgeStatusResponse>(body)
    }

    private suspend fun isDirectReachable(address: String, port: Int): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$address:$port/api/bridge/status")
                .get()
                .build()
            val response = probeClient.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isGatewayOnline(instanceId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$GATEWAY_URL/api/gateway/status?instance_id=$instanceId")
                .get()
                .build()
            val response = probeClient.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            val online = body?.let {
                runCatching { json.parseToJsonElement(it).jsonObject["online"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
            } ?: false
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网关状态 instance=$instanceId code=${response.code} online=$online")
            online
        } catch (e: Exception) {
            logStore.log(SyncLogLevel.Error, SyncLogCategory.Connection, "网关检查失败 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun startSse() {
        val profile = activeProfile() ?: return
        val baseUrl = apiClient.baseUrl
        val instanceId = profile.instanceId.ifBlank { null }
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "启动SSE url=$baseUrl iid=$instanceId")
        sseJob?.cancel()
        syncSseHandler.start()
        syncSseClient.instanceId = instanceId
        sseJob = scope.launch {
            syncSseClient.connect(baseUrl, forceRestart = true)
        }
    }

    private fun stopSse() {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "停止SSE")
        sseJob?.cancel()
        sseEventRepository.disconnect()
        syncSseClient.disconnect()
    }

    private fun startBackoff(delayMs: Long) {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "开始退避 delay=$delayMs ms")
        backoffJob?.cancel()
        backoffJob = scope.launch {
            delay(delayMs)
            sendEvent(ConnEvent.BackoffExpired)
        }
    }

    private fun stopBackoff() {
        backoffJob?.cancel()
        backoffJob = null
    }

    private fun startDirectCheckLoop() {
        val profile = activeProfile() ?: return
        val address = profile.address
        val port = profile.port
        stopDirectCheckLoop()
        directCheckJob = scope.launch {
            while (true) {
                delay(DIRECT_CHECK_INTERVAL_MS)
                if (isDirectReachable(address, port)) {
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "直连恢复 切回直连")
                    sendEvent(ConnEvent.ProbeOk(Route.Direct(address, port)))
                    break
                }
            }
        }
    }

    private fun stopDirectCheckLoop() {
        directCheckJob?.cancel()
        directCheckJob = null
    }

    companion object {
        private const val GATEWAY_URL = "https://gateway.clawmate.net"
        private const val DIRECT_CHECK_INTERVAL_MS = 30_000L
    }
}
