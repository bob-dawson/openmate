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
    data class BridgeNotBridge(val profile: ServerProfile) : ConnEvent()
    data class BridgeNeedsRepair(val profile: ServerProfile) : ConnEvent()

    data object NetworkIsWifi : ConnEvent()
    data object NetworkIsMobile : ConnEvent()
    data object NetworkIsNone : ConnEvent()

    data class SseConnected(val route: Route) : ConnEvent()
    data class SseFailed(val route: Route, val msg: String? = null) : ConnEvent()
    data class SseStreamClosed(val route: Route) : ConnEvent()

    data object BackoffExpired : ConnEvent()

    fun logText(): String = when (this) {
        is Connect -> "Connect profile=${profile.id} iid='${profile.instanceId}'"
        is Disconnect -> "Disconnect"
        is Retry -> "Retry"
        is RepairCompleted -> "RepairCompleted id=$profileId"
        is NetworkAvailable -> "NetworkAvailable"
        is NetworkLost -> "NetworkLost"
        is AppForegrounded -> "AppForegrounded"
        is ProbeOk -> "ProbeOk route=${route.logText()}"
        is ProbeFail -> "ProbeFail route=${route.logText()} reason=$reason"
        is BridgeNotBridge -> "BridgeNotBridge"
        is BridgeNeedsRepair -> "BridgeNeedsRepair"
        is NetworkIsWifi -> "NetworkIsWifi"
        is NetworkIsMobile -> "NetworkIsMobile"
        is NetworkIsNone -> "NetworkIsNone"
        is SseConnected -> "SseConnected route=${route.logText()}"
        is SseFailed -> "SseFailed route=${route.logText()} msg=$msg"
        is SseStreamClosed -> "SseStreamClosed route=${route.logText()}"
        is BackoffExpired -> "BackoffExpired"
    }
}