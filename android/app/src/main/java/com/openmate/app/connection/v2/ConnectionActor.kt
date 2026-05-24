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

    fun logText(): String = when (this) {
        is Idle -> "Idle(profile=${profile?.id})"
        is ProbingNetwork -> "ProbingNetwork(profile=${profile.id}, attempt=$attempt)"
        is WaitingForNetwork -> "WaitingForNetwork(profile=${profile.id}, attempt=$attempt)"
        is ProbingDirect -> "ProbingDirect(profile=${profile.id}, attempt=$attempt)"
        is ProbingGateway -> "ProbingGateway(profile=${profile.id}, attempt=$attempt)"
        is Connecting -> "Connecting(profile=${profile.id}, route=${route.logText()}, attempt=$attempt)"
        is Connected -> "Connected(profile=${profile.id}, route=${route.logText()}, attempt=$attempt)"
        is Recovering -> "Recovering(profile=${profile.id}, attempt=$attempt)"
        is Failed -> "Failed(profile=${profile.id}, reason=$reason)"
        is NeedsRepair -> "NeedsRepair(profile=${profile.id})"
    }
}

sealed class Route {
    data class Direct(val address: String, val port: Int) : Route()
    data class Gateway(val instanceId: String) : Route()
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
    private val connecting = ConnState.Connecting(DUMMY_PROFILE, Route.Direct("", 0))
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
                transition<ConnEvent.NetworkIsWifi> {
                    targetState = probingDirect
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        _state.value = ConnState.ProbingDirect(s.profile, s.attempt)
                    }
                }
                transition<ConnEvent.NetworkIsMobile> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ProbingNetwork
                        _state.value = ConnState.ProbingGateway(s.profile, s.attempt)
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
                        onEffect(ConnEffect.ClearApiClient)
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
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.WaitingForNetwork
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(probingDirect) {
                onEntry {
                    val s = _state.value as ConnState.ProbingDirect
                    onEffect(ConnEffect.ProbeDirect(s.profile.address, s.profile.port))
                }
                transition<ConnEvent.ProbeOk> {
                    targetState = connecting
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.Connecting(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.ProbeFail> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        val p = s.profile
                        if (p.instanceId.isNotEmpty()) {
                            _state.value = ConnState.ProbingGateway(p, s.attempt)
                        } else {
                            _state.value = ConnState.Failed(p, reason = "Direct unreachable, no gateway configured")
                            targetState = failed
                        }
                    }
                }
                transition<ConnEvent.BridgeNotBridge> {
                    targetState = probingGateway
                    onTriggered {
                        val s = _state.value as ConnState.ProbingDirect
                        val p = s.profile
                        if (p.instanceId.isNotEmpty()) {
                            _state.value = ConnState.ProbingGateway(p, s.attempt)
                        } else {
                            _state.value = ConnState.Failed(p, reason = "Not a Bridge server, no gateway configured")
                            targetState = failed
                        }
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
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.ProbingDirect
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(probingGateway) {
                onEntry {
                    val s = _state.value as ConnState.ProbingGateway
                    onEffect(ConnEffect.ProbeGateway(s.profile.instanceId))
                }
                transition<ConnEvent.ProbeOk> {
                    targetState = connecting
                    onTriggered {
                        val s = _state.value as ConnState.ProbingGateway
                        val e = it.event as ConnEvent.ProbeOk
                        _state.value = ConnState.Connecting(s.profile, e.route, s.attempt)
                    }
                }
                transition<ConnEvent.ProbeFail> {
                    targetState = failed
                    onTriggered {
                        val s = _state.value as ConnState.ProbingGateway
                        _state.value = ConnState.Failed(s.profile, reason = "All routes failed")
                    }
                }
                transition<ConnEvent.Disconnect> {
                    targetState = idle
                    onTriggered {
                        onEffect(ConnEffect.StopSse)
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.ProbingGateway
                        _state.value = ConnState.Idle(s.profile)
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
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Recovering
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(failed) {
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
                        onEffect(ConnEffect.ClearApiClient)
                        val s = _state.value as ConnState.Failed
                        _state.value = ConnState.Idle(s.profile)
                    }
                }
            }

            addState(needsRepair) {
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
