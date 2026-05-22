package com.openmate.app.connection.v2

import com.openmate.core.domain.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import ru.nsk.kstatemachine.state.*
import ru.nsk.kstatemachine.statemachine.createStateMachine
import ru.nsk.kstatemachine.transition.onTriggered

sealed class ConnState(name: String? = null) : DefaultState(name) {
    data class Idle(val profile: ServerProfile? = null) : ConnState("Idle")

    data class Probing(
        val profile: ServerProfile,
        val tried: Set<Route> = emptySet(),
        val attempt: Int = 0,
    ) : ConnState("Probing")

    data class Connecting(
        val profile: ServerProfile,
        val route: Route,
        val attempt: Int = 0,
    ) : ConnState("Connecting")

    data class Connected(
        val profile: ServerProfile,
        val route: Route,
        val attempt: Int = 0,
    ) : ConnState("Connected")

    data class Recovering(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState("Recovering")

    data class Failed(val profile: ServerProfile, val reason: String = "No available route") : ConnState("Failed")

    data class NeedsRepair(val profile: ServerProfile) : ConnState("NeedsRepair")
}

sealed class Route {
    data class Direct(val address: String, val port: Int) : Route()
    data class Gateway(val instanceId: String) : Route()
}

class ConnectionActor(
    scope: CoroutineScope,
    private val onEffect: (ConnEffect) -> Unit,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val idle = ConnState.Idle()
    private val probing = ConnState.Probing(DUMMY_PROFILE)
    private val connecting = ConnState.Connecting(DUMMY_PROFILE, Route.Direct("", 0))
    private val connected = ConnState.Connected(DUMMY_PROFILE, Route.Direct("", 0))
    private val recovering = ConnState.Recovering(DUMMY_PROFILE)
    private val failed = ConnState.Failed(DUMMY_PROFILE)
    private val needsRepair = ConnState.NeedsRepair(DUMMY_PROFILE)

    private val machine = runBlocking {
        createStateMachine(scope, "Connection") {
            addInitialState(idle) {
                transition<ConnEvent.Connect> {
                    targetState = probing
                    onTriggered {
                        val e = it.event as ConnEvent.Connect
                        _state.value = ConnState.Probing(e.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        _state.value = ConnState.Idle()
                    }
                }
            }

            addState(probing) {
                onEntry {
                    val s = _state.value as ConnState.Probing
                    if (Route.Gateway(s.profile.instanceId) !in s.tried && s.profile.instanceId.isNotEmpty()) {
                        onEffect(ConnEffect.ProbeGateway(s.profile.instanceId))
                    } else if (Route.Direct(s.profile.address, s.profile.port) !in s.tried) {
                        onEffect(ConnEffect.ProbeDirect(s.profile.address, s.profile.port))
                    }
                }
                transition<ConnEvent.ProbeOk> {
                    targetState = connecting
                    onTriggered {
                        val s = _state.value as ConnState.Probing
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.Connecting(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.ProbeFail> {
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Probing
                        val e = it.event as ConnEvent.ProbeFail
                        val newTried = s.tried + e.route
                        val next = nextProbe(s.profile, newTried)
                        if (next != null) {
                            _state.value = ConnState.Probing(s.profile, newTried, s.attempt)
                        } else {
                            _state.value = ConnState.Failed(s.profile, reason = "All routes failed")
                            targetState = failed
                        }
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Probing
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
                transition<ConnEvent.BridgeNotBridge> {
                    targetState = failed
                    onTriggered {
                        val s = _state.value as ConnState.Probing
                        _state.value = ConnState.Failed(s.profile, reason = "Not a Bridge server")
                    }
                }
                transition<ConnEvent.BridgeNeedsRepair> {
                    targetState = needsRepair
                    onTriggered {
                        val s = _state.value as ConnState.Probing
                        _state.value = ConnState.NeedsRepair(s.profile)
                    }
                }
            }

            addState(connecting) {
                onEntry {
                    val s = _state.value as ConnState.Connecting
                    val baseUrl = baseUrlFor(s.route)
                    val iid = (s.route as? Route.Gateway)?.instanceId
                    onEffect(ConnEffect.SetApiClient(baseUrl, iid))
                    onEffect(ConnEffect.StartSse(baseUrl, iid))
                }
                transition<ConnEvent.SseConnected> {
                    targetState = connected
                    onTriggered {
                        val s = _state.value as ConnState.Connecting
                        val e = it.event as ConnEvent.SseConnected
                        _state.value = ConnState.Connected(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.SseFailed> {
                    targetState = recovering
                    onTriggered {
                        val s = _state.value as ConnState.Connecting
                        _state.value = ConnState.Recovering(s.profile, attempt = s.attempt + 1)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Connecting
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(connected) {
                onEntry {
                    val s = _state.value as ConnState.Connected
                    onEffect(ConnEffect.RefreshSessions)
                    onEffect(ConnEffect.SaveProfile(s.profile))
                    if (s.route is Route.Gateway) {
                        onEffect(ConnEffect.StartDirectCheckLoop(s.profile.address, s.profile.port))
                    }
                }
                onExit {
                    onEffect(ConnEffect.StopDirectCheckLoop)
                }
                transition<ConnEvent.ProbeOk> {
                    guard = { (event as ConnEvent.ProbeOk).route is Route.Direct && (_state.value as ConnState.Connected).route is Route.Gateway }
                    targetState = connecting
                    onTriggered {
                        val s = _state.value as ConnState.Connected
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.Connecting(s.profile, e.route, s.attempt)
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
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        onEffect(ConnEffect.ClearApiClient)
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
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.Probing(s.profile, attempt = s.attempt)
                    }
                }
                transition<ConnEvent.NetworkAvailable> {
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.Probing(s.profile, attempt = s.attempt)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(failed) {
                transition<ConnEvent.NetworkAvailable> {
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Probing(s.profile)
                    }
                }
                transition<ConnEvent.AppForegrounded> {
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Probing(s.profile)
                    }
                }
                transition<ConnEvent.Retry> {
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Probing(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(needsRepair) {
                transition<ConnEvent.RepairCompleted> {
                    guard = { (event as ConnEvent.RepairCompleted).profileId == (_state.value as ConnState.NeedsRepair).profile.id }
                    targetState = probing
                    onTriggered {
                        val s = _state.value as ConnState.NeedsRepair
                        _state.value = ConnState.Probing(s.profile)
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.NeedsRepair
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }
        }
    }

    suspend fun processEvent(event: ConnEvent) {
        machine.processEvent(event)
    }

    private fun nextProbe(profile: ServerProfile, tried: Set<Route>): Route? {
        val gw = Route.Gateway(profile.instanceId)
        val dr = Route.Direct(profile.address, profile.port)
        return when {
            gw !in tried && profile.instanceId.isNotEmpty() -> gw
            dr !in tried -> dr
            else -> null
        }
    }

    private fun baseUrlFor(route: Route): String = when (route) {
        is Route.Direct -> "http://${route.address}:${route.port}"
        is Route.Gateway -> GATEWAY_URL
    }

    private fun backoffMs(attempt: Int): Long =
        minOf(INITIAL_BACKOFF_MS * (1L shl minOf(attempt, 4)), MAX_BACKOFF_MS)

    companion object {
        private const val GATEWAY_URL = "https://gateway.clawmate.net"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private val DUMMY_PROFILE = ServerProfile(
            id = "", name = "", address = "", port = 0,
            instanceId = "", createdAt = 0L,
        )
    }
}