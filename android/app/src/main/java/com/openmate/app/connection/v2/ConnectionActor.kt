package com.openmate.app.connection.v2

import com.openmate.core.domain.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import ru.nsk.kstatemachine.state.*
import ru.nsk.kstatemachine.statemachine.createStateMachine
import ru.nsk.kstatemachine.transition.onTriggered

sealed class ConnState(name: String? = null) : DefaultState(name) {
    data class Idle(val profile: ServerProfile? = null) : ConnState("Idle")

    data class ProbingNetwork(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("ProbingNetwork")

    data class WaitingForNetwork(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("WaitingForNetwork")

    data class ProbingDirect(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("ProbingDirect")

    data class ProbingGateway(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("ProbingGateway")

    data class ConnectingCached(
        val profile: ServerProfile,
        val route: Route,
        val attempt: Int = 0,
    ) : ConnState("ConnectingCached")

    data class ConnectingFresh(
        val profile: ServerProfile,
        val route: Route,
        val attempt: Int = 0,
    ) : ConnState("ConnectingFresh")

    data class Connected(
        val profile: ServerProfile,
        val route: Route,
        val attempt: Int = 0,
    ) : ConnState("Connected")

    data class Recovering(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("Recovering")

    data class Failed(val profile: ServerProfile, val reason: String = "No available route", val attempt: Int = 0) : ConnState("Failed")

    data class NeedsRepair(val profile: ServerProfile) : ConnState("NeedsRepair")

    fun withProfile(newProfile: ServerProfile): ConnState = when (this) {
        is Idle -> this
        is ProbingNetwork -> copy(profile = newProfile)
        is WaitingForNetwork -> copy(profile = newProfile)
        is ProbingDirect -> copy(profile = newProfile)
        is ProbingGateway -> copy(profile = newProfile)
        is ConnectingCached -> copy(profile = newProfile)
        is ConnectingFresh -> copy(profile = newProfile)
        is Connected -> copy(profile = newProfile)
        is Recovering -> copy(profile = newProfile)
        is Failed -> copy(profile = newProfile)
        is NeedsRepair -> copy(profile = newProfile)
    }

    fun logText(): String = when (this) {
        is Idle -> "Idle(profile=${profile?.id})"
        is ProbingNetwork -> "ProbingNetwork(profile=${profile.id}, attempt=$attempt)"
        is WaitingForNetwork -> "WaitingForNetwork(profile=${profile.id}, attempt=$attempt)"
        is ProbingDirect -> "ProbingDirect(profile=${profile.id}, attempt=$attempt)"
        is ProbingGateway -> "ProbingGateway(profile=${profile.id}, attempt=$attempt)"
        is ConnectingCached -> "ConnectingCached(profile=${profile.id}, route=${route.logText()}, attempt=$attempt)"
        is ConnectingFresh -> "ConnectingFresh(profile=${profile.id}, route=${route.logText()}, attempt=$attempt)"
        is Connected -> "Connected(profile=${profile.id}, route=${route.logText()}, attempt=$attempt)"
        is Recovering -> "Recovering(profile=${profile.id}, attempt=$attempt)"
        is Failed -> "Failed(profile=${profile.id}, reason=$reason, attempt=$attempt)"
        is NeedsRepair -> "NeedsRepair(profile=${profile.id})"
    }
}

sealed class Route {
    data class Direct(val address: String, val port: Int) : Route()
    data class Gateway(val instanceId: String) : Route()
    fun logText(): String = when (this) {
        is Direct -> "Direct($address:$port)"
        is Gateway -> "Gateway($instanceId)"
    }
}

class ConnectionActor(
    private val onEffect: (ConnEffect) -> Unit,
) {
    private val machineScope = CoroutineScope(SupervisorJob() + newSingleThreadContext("conn-sm"))
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val idle = ConnState.Idle()
    private val probingNetwork = ConnState.ProbingNetwork(DUMMY_PROFILE)
    private val waitingForNetwork = ConnState.WaitingForNetwork(DUMMY_PROFILE)
    private val probingDirect = ConnState.ProbingDirect(DUMMY_PROFILE)
    private val probingGateway = ConnState.ProbingGateway(DUMMY_PROFILE)
    private val connectingCached = ConnState.ConnectingCached(DUMMY_PROFILE, Route.Direct("", 0))
    private val connectingFresh = ConnState.ConnectingFresh(DUMMY_PROFILE, Route.Direct("", 0))
    private val connected = ConnState.Connected(DUMMY_PROFILE, Route.Direct("", 0))
    private val recovering = ConnState.Recovering(DUMMY_PROFILE)
    private val failed = ConnState.Failed(DUMMY_PROFILE)
    private val needsRepair = ConnState.NeedsRepair(DUMMY_PROFILE)

    private val machine = runBlocking {
        createStateMachine(machineScope, "Connection") {
            addInitialState(idle) {
                transition<ConnEvent.Connect> {
                    targetState = probingNetwork
                    onTriggered {
                        val e = it.event as ConnEvent.Connect
                        _state.value = ConnState.ProbingNetwork(e.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        _state.value = ConnState.Idle()
                    }
                }
            }

            addState(probingNetwork) {
                onEntry {
                    val s = _state.value as ConnState.ProbingNetwork
                    onEffect(ConnEffect.CheckNetwork(s.profile, s.attempt))
                }
                transition<ConnEvent.CacheDirectOk> {
                    targetState = connectingCached
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        val e = it.event as ConnEvent.CacheDirectOk
                        _state.value = ConnState.ConnectingCached(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.CacheGatewayOk> {
                    targetState = connectingCached
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        val e = it.event as ConnEvent.CacheGatewayOk
                        _state.value = ConnState.ConnectingCached(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.CacheNone> {
                    targetState = probingDirect
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        _state.value = ConnState.ProbingDirect(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.NetworkIsNone> {
                    targetState = waitingForNetwork
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        _state.value = ConnState.WaitingForNetwork(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(waitingForNetwork) {
                transition<ConnEvent.NetworkAvailable> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.WaitingForNetwork
                        _state.value = ConnState.ProbingNetwork(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.AppForegrounded> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.WaitingForNetwork
                        _state.value = ConnState.ProbingNetwork(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        val s = _state.value as ConnState.WaitingForNetwork
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(probingDirect) {
                onEntry {
                    onEffect(ConnEffect.ProbeDirect)
                }
                transition<ConnEvent.ProbeOk> {
                    targetState = connectingFresh
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.ConnectingFresh(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.ProbeFail> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        _state.value = ConnState.ProbingGateway(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.BridgeNotBridge> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        _state.value = ConnState.ProbingGateway(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.BridgeNeedsRepair> {
                    targetState = needsRepair
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        _state.value = ConnState.NeedsRepair(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.ProbingDirect
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(probingGateway) {
                onEntry {
                    onEffect(ConnEffect.ProbeGateway)
                }
                transition<ConnEvent.ProbeOk> {
                    targetState = connectingFresh
                    onTriggered {
                        val s = _state.value as ConnState.ProbingGateway
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.ConnectingFresh(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.ProbeFail> {
                    targetState = failed
                    onTriggered {
                        val s = _state.value as ConnState.ProbingGateway
                        _state.value = ConnState.Failed(s.profile, reason = "All routes failed", attempt = s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.ProbingGateway
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(connectingCached) {
                onEntry {
                    val s = _state.value as ConnState.ConnectingCached
                    onEffect(ConnEffect.StartSse(s.route))
                }
                transition<ConnEvent.SseConnected> {
                    targetState = connected
                    onTriggered {
                        val s = _state.value as ConnState.ConnectingCached
                        val e = it.event as ConnEvent.SseConnected
                        _state.value = ConnState.Connected(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.SseFailed> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ConnectingCached
                        onEffect(ConnEffect.ClearCache(s.profile.id))
                        _state.value = ConnState.ProbingGateway(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.ConnectingCached
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(connectingFresh) {
                onEntry {
                    val s = _state.value as ConnState.ConnectingFresh
                    onEffect(ConnEffect.StartSse(s.route))
                }
                transition<ConnEvent.SseConnected> {
                    targetState = connected
                    onTriggered {
                        val s = _state.value as ConnState.ConnectingFresh
                        val e = it.event as ConnEvent.SseConnected
                        _state.value = ConnState.Connected(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.SseFailed> {
                    targetState = recovering
                    onTriggered {
                        val s = _state.value as ConnState.ConnectingFresh
                        _state.value = ConnState.Recovering(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.ConnectingFresh
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(connected) {
                onEntry {
                    val s = _state.value as ConnState.Connected
                    onEffect(ConnEffect.RefreshSessions)
                    onEffect(ConnEffect.UpdateLastConnectedAt(s.profile.id))
                    when (s.route) {
                        is Route.Direct -> onEffect(ConnEffect.WriteCacheDirect(s.profile.id))
                        is Route.Gateway -> {
                            onEffect(ConnEffect.WriteCacheGateway(s.profile.id))
                            onEffect(ConnEffect.StartDirectCheckLoop)
                        }
                    }
                }
                onExit {
                    onEffect(ConnEffect.StopDirectCheckLoop)
                }
                transition<ConnEvent.ProbeOk> {
                    guard = { (event as ConnEvent.ProbeOk).route is Route.Direct && (_state.value as ConnState.Connected).route is Route.Gateway }
                    targetState = connectingFresh
                    onTriggered {
                        val s = _state.value as ConnState.Connected
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.ConnectingFresh(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.SseFailed> {
                    targetState = recovering
                    onTriggered {
                        val s = _state.value as ConnState.Connected
                        _state.value = ConnState.Recovering(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.SseStreamClosed> {
                    targetState = recovering
                    onTriggered {
                        val s = _state.value as ConnState.Connected
                        _state.value = ConnState.Recovering(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.NetworkLost> {
                    targetState = recovering
                    onTriggered {
                        val s = _state.value as ConnState.Connected
                        _state.value = ConnState.Recovering(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.AppBackgrounded> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.Connected
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.Connected
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(recovering) {
                onEntry {
                    val s = _state.value as ConnState.Recovering
                    onEffect(ConnEffect.StartBackoff(backoffMs(s.attempt)))
                }
                onExit {
                    onEffect(ConnEffect.StopBackoff)
                }
                transition<ConnEvent.BackoffExpired> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.ProbingNetwork(s.profile, attempt = s.attempt)
                    }
                }
                transition<ConnEvent.NetworkAvailable> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.ProbingNetwork(s.profile, attempt = s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(failed) {
                onEntry {
                    val s = _state.value as ConnState.Failed
                    onEffect(ConnEffect.StartBackoff(backoffMs(s.attempt)))
                }
                onExit {
                    onEffect(ConnEffect.StopBackoff)
                }
                transition<ConnEvent.BackoffExpired> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.ProbingNetwork(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.Connect> {
                    targetState = probingNetwork
                    onTriggered {
                        val e = it.event as ConnEvent.Connect
                        _state.value = ConnState.ProbingNetwork(e.profile)
                    }
                }
                transition<ConnEvent.NetworkAvailable> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.ProbingNetwork(s.profile)
                    }
                }
                transition<ConnEvent.AppForegrounded> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.ProbingNetwork(s.profile)
                    }
                }
                transition<ConnEvent.Retry> {
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.ProbingNetwork(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(needsRepair) {
                transition<ConnEvent.Connect> {
                    targetState = probingNetwork
                    onTriggered {
                        val e = it.event as ConnEvent.Connect
                        _state.value = ConnState.ProbingNetwork(e.profile)
                    }
                }
                transition<ConnEvent.RepairCompleted> {
                    guard = { (event as ConnEvent.RepairCompleted).profileId == (_state.value as ConnState.NeedsRepair).profile.id }
                    targetState = probingNetwork
                    onTriggered {
                        val s = _state.value as ConnState.NeedsRepair
                        _state.value = ConnState.ProbingNetwork(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        val s = _state.value as ConnState.NeedsRepair
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }
        }
    }

    fun updateProfile(profile: ServerProfile) {
        val s = _state.value
        val currentId = when (s) {
            is ConnState.Idle -> s.profile?.id
            is ConnState.ProbingNetwork -> s.profile.id
            is ConnState.WaitingForNetwork -> s.profile.id
            is ConnState.ProbingDirect -> s.profile.id
            is ConnState.ProbingGateway -> s.profile.id
            is ConnState.ConnectingCached -> s.profile.id
            is ConnState.ConnectingFresh -> s.profile.id
            is ConnState.Connected -> s.profile.id
            is ConnState.Recovering -> s.profile.id
            is ConnState.Failed -> s.profile.id
            is ConnState.NeedsRepair -> s.profile.id
        }
        if (currentId == profile.id) {
            _state.value = s.withProfile(profile)
            if (s is ConnState.Connected && s.route is Route.Gateway) {
                onEffect(ConnEffect.RestartDirectCheckLoop)
            }
        }
    }

    suspend fun processEvent(event: ConnEvent) {
        machine.processEvent(event)
    }

    private fun backoffMs(attempt: Int): Long =
        minOf(INITIAL_BACKOFF_MS * (1L shl minOf(attempt, 4)), MAX_BACKOFF_MS)

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private val DUMMY_PROFILE = ServerProfile(
            id = "", name = "", address = "", port = 0,
            instanceId = "", createdAt = 0L,
        )
    }
}
