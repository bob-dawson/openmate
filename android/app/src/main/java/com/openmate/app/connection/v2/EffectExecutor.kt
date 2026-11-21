package com.openmate.app.connection.v2

import com.openmate.app.connection.NetworkChangeMonitor
import com.openmate.app.connection.RouteEvidence
import com.openmate.app.connection.RouteEvidenceAggregator
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.CachedRoute
import com.openmate.core.data.LanDiscoveryStateStore
import com.openmate.core.data.RouteCache
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.network.ActiveProfileProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val networkMonitor: NetworkChangeMonitor,
    private val routeCache: RouteCache,
    private val lanDiscoveryStore: LanDiscoveryStateStore,
    private val routeEvidenceAggregator: RouteEvidenceAggregator,
    probeClientBase: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val probeClient = probeClientBase.newBuilder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val lanProbeClient = probeClientBase.newBuilder()
        .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private val lanProber = LanProber(lanProbeClient, json)

    private var backoffJob: Job? = null
    private var sseJob: Job? = null
    private var directCheckJob: Job? = null

    private fun activeProfile(): ServerProfile? = activeProfileProvider.getActiveProfile()

    fun execute(effect: ConnEffect) {
        when (effect) {
            is ConnEffect.CheckNetwork -> checkNetwork()
            is ConnEffect.ProbeDirect -> probeDirect()
            is ConnEffect.ProbeGateway -> probeGateway()
            is ConnEffect.StartSse -> startSse(effect.route)
            is ConnEffect.StopSse -> stopSse()
            is ConnEffect.StartBackoff -> startBackoff(effect.delayMs)
            is ConnEffect.StopBackoff -> stopBackoff()
            is ConnEffect.RefreshSessions -> scope.launch { sessionRepository.refreshSessionStatuses() }
            is ConnEffect.UpdateLastConnectedAt -> scope.launch {
                val profile = profileRepository.getById(effect.profileId)
                    ?: activeProfile()?.takeIf { it.id == effect.profileId }
                if (profile != null) {
                    profileRepository.save(profile.copy(lastConnectedAt = System.currentTimeMillis()))
                }
            }
            is ConnEffect.StartDirectCheckLoop -> startDirectCheckLoop()
            is ConnEffect.StopDirectCheckLoop -> stopDirectCheckLoop()
            is ConnEffect.RestartDirectCheckLoop -> { stopDirectCheckLoop(); startDirectCheckLoop() }
            is ConnEffect.WriteCacheDirect -> scope.launch { routeCache.setDirect(effect.profileId) }
            is ConnEffect.WriteCacheGateway -> scope.launch { routeCache.setGateway(effect.profileId) }
            is ConnEffect.ClearCache -> scope.launch { routeCache.clear(effect.profileId) }
        }
    }

    private fun checkNetwork() {
        val profile = activeProfile()
        scope.launch {
            val hasNetwork = networkMonitor.hasInternet()
            if (!hasNetwork) {
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网络检测 hasNetwork=false")
                sendEvent(ConnEvent.NetworkIsNone)
                return@launch
            }
            val cached = profile?.id?.let { routeCache.get(it) }
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网络检测 hasNetwork=true cache=$cached")
            when (cached) {
                CachedRoute.DIRECT -> {
                    val p = profile ?: return@launch
                    sendEvent(ConnEvent.CacheDirectOk(Route.Direct(p.address, p.port)))
                }
                CachedRoute.GATEWAY -> {
                    val p = profile ?: return@launch
                    if (p.instanceId.isNotEmpty() && p.gatewayEnabled) {
                        sendEvent(ConnEvent.CacheGatewayOk(Route.Gateway(p.instanceId)))
                    } else {
                        sendEvent(ConnEvent.CacheNone)
                    }
                }
                null -> sendEvent(ConnEvent.CacheNone)
            }
        }
    }

    private fun probeDirect() {
        val profile = activeProfile() ?: return
        val address = profile.address
        val port = profile.port
        scope.launch(Dispatchers.IO) {
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
                routeEvidenceAggregator.record(
                    RouteEvidence.ProbeSuccess(ConnectionRoute.Direct(address, port), System.currentTimeMillis())
                )
                sendEvent(ConnEvent.ProbeOk(Route.Direct(address, port)))
            } catch (e: Exception) {
                logStore.log(SyncLogLevel.Warn, SyncLogCategory.Connection, "直连探测失败 address=$address:$port ${e.javaClass.simpleName}: ${e.message}")
                routeEvidenceAggregator.record(
                    RouteEvidence.ProbeFailure(ConnectionRoute.Direct(address, port), System.currentTimeMillis(), e.message)
                )
                sendEvent(ConnEvent.ProbeFail(Route.Direct(address, port)))
            }
        }
    }

    private fun probeGateway() {
        val profile = activeProfile() ?: return
        if (!profile.gatewayEnabled) {
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网关已禁用，跳过探测")
            routeEvidenceAggregator.record(
                RouteEvidence.ProbeFailure(ConnectionRoute.Gateway(profile.instanceId), System.currentTimeMillis(), "gateway disabled")
            )
            sendEvent(ConnEvent.ProbeFail(Route.Gateway(profile.instanceId)))
            return
        }
        val instanceId = profile.instanceId
        scope.launch(Dispatchers.IO) {
            val success = isGatewayOnline(instanceId)
            if (success) {
                routeEvidenceAggregator.record(
                    RouteEvidence.ProbeSuccess(ConnectionRoute.Gateway(instanceId), System.currentTimeMillis())
                )
                sendEvent(ConnEvent.ProbeOk(Route.Gateway(instanceId)))
            } else {
                routeEvidenceAggregator.record(
                    RouteEvidence.ProbeFailure(ConnectionRoute.Gateway(instanceId), System.currentTimeMillis(), "gateway offline")
                )
                sendEvent(ConnEvent.ProbeFail(Route.Gateway(instanceId)))
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

    private fun startSse(route: Route) {
        val profile = activeProfile() ?: return
        if (route is Route.Gateway && !profile.gatewayEnabled) {
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "网关已禁用，跳过SSE连接")
            sendEvent(ConnEvent.SseFailed(route, "Gateway disabled"))
            return
        }
        val baseUrl = when (route) {
            is Route.Direct -> "http://${route.address}:${route.port}"
            is Route.Gateway -> GATEWAY_URL
        }
        val instanceId = profile.instanceId.ifBlank { null }
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "启动SSE url=$baseUrl iid=$instanceId route=${route.logText()}")
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
        stopDirectCheckLoop()
        directCheckJob = scope.launch {
            while (true) {
                val profile = activeProfile() ?: return@launch
                val now = System.currentTimeMillis()
                var state = lanDiscoveryStore.load(profile.id)
                val fingerprint = networkMonitor.networkFingerprint().orEmpty()
                val decision = LanDiscoveryPolicy.decide(
                    state = state,
                    now = now,
                    fingerprint = fingerprint,
                    intervalMs = LanDiscoveryPolicy.intervalMs(state.failures),
                )

                if (decision.reset) {
                    state = state.copy(failures = 0)
                    logStore.log(
                        SyncLogLevel.Info,
                        SyncLogCategory.Connection,
                        "网络环境探测重置 ${if (decision.envChanged) "network-changed" else "gateway-recovered"} " +
                            "fingerprint=$fingerprint",
                    )
                }

                state = state.copy(fingerprint = fingerprint, lastGatewaySeenAt = now)
                lanDiscoveryStore.save(profile.id, state)

                if (decision.shouldProbe) {
                    lanDiscoveryStore.save(profile.id, state.copy(lastProbeAt = System.currentTimeMillis()))
                    val result = discoverAndSwitch(profile)
                    if (result.switched) {
                        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "局域网直连已恢复，已切换路由")
                        return@launch
                    }
                    val latest = lanDiscoveryStore.load(profile.id)
                    val candidatesChanged = result.candidatesSignature != null &&
                        latest.candidatesSignature.isNotEmpty() &&
                        latest.candidatesSignature != result.candidatesSignature
                    val nextFailures = LanDiscoveryPolicy.nextFailures(latest.failures, candidatesChanged)
                    lanDiscoveryStore.save(
                        profile.id,
                        latest.copy(
                            failures = nextFailures,
                            candidatesSignature = result.candidatesSignature ?: latest.candidatesSignature,
                        ),
                    )
                    logStore.log(
                        SyncLogLevel.Info,
                        SyncLogCategory.Connection,
                        "局域网探测失败 failures=$nextFailures " +
                            "nextIn=${LanDiscoveryPolicy.intervalMs(nextFailures) / 1000}s",
                    )
                }

                delay(LOOP_TICK_MS)
            }
        }
    }

    private data class DiscoveryOutcome(
        val switched: Boolean,
        val candidatesSignature: String?,
    )

    private suspend fun discoverAndSwitch(profile: ServerProfile): DiscoveryOutcome {
        val candidates = mutableListOf<LanCandidate>()
        if (profile.address.isNotBlank()) {
            candidates += LanCandidate(
                ip = profile.address,
                port = profile.port,
                scope = LanCandidateRanking.scopeOf(profile.address),
            )
        }
        val remote = runCatching { apiClient.bridgeLanCandidates() }.getOrNull()
        remote?.candidates?.forEach { dto ->
            if (dto.ip.isNotBlank()) {
                candidates += LanCandidate(
                    ip = dto.ip,
                    port = if (dto.port > 0) dto.port else profile.port,
                    scope = dto.scope,
                )
            }
        }
        val signature = remote?.candidates
            ?.map { "${it.ip}:${it.port}:${it.scope}" }
            ?.sorted()
            ?.joinToString(",")
        val ranked = LanCandidateRanking.rank(
            candidates = candidates,
            localIpv4 = networkMonitor.localIpv4Addresses(),
            allowPrivate = !networkMonitor.isCellularActive(),
        )
        if (ranked.isEmpty()) return DiscoveryOutcome(switched = false, candidatesSignature = signature)

        val best = lanProber.probeBest(profile.instanceId, ranked)
            ?: return DiscoveryOutcome(switched = false, candidatesSignature = signature)

        if (best.ip != profile.address || best.port != profile.port) {
            profileRepository.save(profile.copy(address = best.ip, port = best.port))
            logStore.log(
                SyncLogLevel.Info,
                SyncLogCategory.Connection,
                "局域网地址更新 ${profile.address}:${profile.port} -> ${best.ip}:${best.port}",
            )
        }
        routeEvidenceAggregator.record(
            RouteEvidence.ProbeSuccess(ConnectionRoute.Direct(best.ip, best.port), System.currentTimeMillis())
        )
        sendEvent(ConnEvent.ProbeOk(Route.Direct(best.ip, best.port)))
        return DiscoveryOutcome(switched = true, candidatesSignature = signature)
    }

    private fun stopDirectCheckLoop() {
        directCheckJob?.cancel()
        directCheckJob = null
    }

    companion object {
        private const val GATEWAY_URL = "https://gateway.clawmate.net"
        private const val LOOP_TICK_MS = 30_000L
    }
}
