package com.openmate.app.connection.v2

import ru.nsk.kstatemachine.event.Event
import com.openmate.core.domain.model.ServerProfile

sealed class ConnEvent : Event {
    data class Connect(val profile: ServerProfile) : ConnEvent()
    data object Disconnect : ConnEvent()
    data object Retry : ConnEvent()
    data class RepairCompleted(val profileId: String) : ConnEvent()

    data object NetworkAvailable : ConnEvent()
    data object NetworkLost : ConnEvent()
    data object AppForegrounded : ConnEvent()

    data class ProbeOk(val route: Route) : ConnEvent()
    data class ProbeFail(val route: Route, val reason: String? = null) : ConnEvent()

    data class SseConnected(val route: Route) : ConnEvent()
    data class SseFailed(val route: Route, val msg: String? = null) : ConnEvent()
    data class SseStreamClosed(val route: Route) : ConnEvent()

    data object BackoffExpired : ConnEvent()

    data class BridgeNotBridge(val profile: ServerProfile) : ConnEvent()
    data class BridgeNeedsRepair(val profile: ServerProfile) : ConnEvent()
}