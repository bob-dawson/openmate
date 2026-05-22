package com.openmate.app.connection.v2

import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request

class EffectExecutor(
    private val scope: CoroutineScope,
    private val sendEvent: (ConnEvent) -> Unit,
    private val apiClient: OpencodeApiClient,
    private val gatewayInterceptor: GatewayInterceptor,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val profileRepository: ServerProfileRepository,
    private val tokenStore: TokenStore,
    private val logStore: SyncLogStore,
) {
    private var backoffJob: Job? = null
    private var sseJob: Job? = null
    private var directCheckJob: Job? = null

    fun execute(effect: ConnEffect) {
        when (effect) {
            is ConnEffect.ProbeGateway -> probeGateway(effect.instanceId)
            is ConnEffect.ProbeDirect -> probeDirect(effect.address, effect.port)
            is ConnEffect.StartSse -> startSse(effect.baseUrl, effect.instanceId)
            is ConnEffect.StopSse -> stopSse()
            is ConnEffect.StartBackoff -> startBackoff(effect.delayMs)
            is ConnEffect.StopBackoff -> stopBackoff()
            is ConnEffect.SetApiClient -> setApiClient(effect.baseUrl, effect.instanceId)
            is ConnEffect.RefreshSessions -> scope.launch { sessionRepository.refreshSessionStatuses() }
            is ConnEffect.SaveProfile -> scope.launch { profileRepository.save(effect.profile) }
            is ConnEffect.StartDirectCheckLoop -> startDirectCheckLoop(effect.address, effect.port)
            is ConnEffect.StopDirectCheckLoop -> stopDirectCheckLoop()
            is ConnEffect.ClearApiClient -> clearApiClient()
        }
    }

    private fun probeGateway(instanceId: String) {
        scope.launch {
            val success = isGatewayOnline(instanceId)
            if (success) {
                sendEvent(ConnEvent.ProbeOk(Route.Gateway(instanceId)))
            } else {
                sendEvent(ConnEvent.ProbeFail(Route.Gateway(instanceId)))
            }
        }
    }

    private fun probeDirect(address: String, port: Int) {
        scope.launch {
            val reachable = isDirectReachable(address, port)
            if (!reachable) {
                sendEvent(ConnEvent.ProbeFail(Route.Direct(address, port)))
                return@launch
            }
            try {
                val status = apiClient.bridgeStatus()
                if (status.bridge.version.isBlank()) {
                    logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "非Bridge服务器", message = "address=$address:$port")
                    sendEvent(ConnEvent.BridgeNotBridge(profileRepository.getAll().first { it.address == address && it.port == port }))
                    return@launch
                }
                if (status.bridge.authEnabled) {
                    val profile = profileRepository.getAll().first { it.address == address && it.port == port }
                    val token = tokenStore.getToken(profile.id)
                    if (token == null) {
                        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "需要配对", message = "profile=${profile.id}")
                        sendEvent(ConnEvent.BridgeNeedsRepair(profile))
                        return@launch
                    }
                }
                if (status.bridge.instanceId.isNotEmpty()) {
                    val profile = profileRepository.getAll().firstOrNull { it.address == address && it.port == port }
                    if (profile != null && status.bridge.instanceId != profile.instanceId) {
                        val updated = profile.copy(instanceId = status.bridge.instanceId)
                        profileRepository.save(updated)
                        logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "更新instanceId", message = "old='${profile.instanceId}' new='${status.bridge.instanceId}'")
                    }
                }
                sendEvent(ConnEvent.ProbeOk(Route.Direct(address, port)))
            } catch (e: Exception) {
                logStore.log(SyncLogLevel.Warn, SyncLogCategory.Gateway, title = "Bridge验证失败", message = e.message ?: "")
                sendEvent(ConnEvent.ProbeFail(Route.Direct(address, port)))
            }
        }
    }

    private suspend fun isGatewayOnline(instanceId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$GATEWAY_URL/api/gateway/status?instance_id=$instanceId")
                .get()
                .build()
            val response = apiClient.peek(request)
            val success = response.isSuccessful
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "网关状态", message = "instance=$instanceId code=${response.code} online=$success")
            response.close()
            success
        } catch (e: Exception) {
            logStore.log(SyncLogLevel.Error, SyncLogCategory.Gateway, title = "网关检查失败", message = "${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun isDirectReachable(address: String, port: Int): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$address:$port/api/bridge/status")
                .get()
                .build()
            val response = apiClient.peek(request)
            val success = response.isSuccessful
            response.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun startSse(baseUrl: String, instanceId: String?) {
        sseJob?.cancel()
        syncSseHandler.start()
        syncSseClient.instanceId = instanceId
        sseJob = scope.launch {
            syncSseClient.connect(baseUrl, forceRestart = true)
        }
    }

    private fun stopSse() {
        sseJob?.cancel()
        sseEventRepository.disconnect()
        syncSseClient.disconnect()
    }

    private fun startBackoff(delayMs: Long) {
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

    private fun setApiClient(baseUrl: String, instanceId: String?) {
        apiClient.baseUrl = baseUrl
        gatewayInterceptor.instanceId = instanceId
    }

    private fun clearApiClient() {
        gatewayInterceptor.instanceId = null
    }

    private fun startDirectCheckLoop(address: String, port: Int) {
        stopDirectCheckLoop()
        directCheckJob = scope.launch {
            while (true) {
                delay(DIRECT_CHECK_INTERVAL_MS)
                if (isDirectReachable(address, port)) {
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Gateway, title = "直连恢复", message = "切回直连")
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
