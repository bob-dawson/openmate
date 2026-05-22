package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile

sealed interface ConnectionEvent {
    data class UserConnect(val profile: ServerProfile) : ConnectionEvent
    data object UserDisconnect : ConnectionEvent
    data object UserRetry : ConnectionEvent
    data class RepairCompleted(val profileId: String) : ConnectionEvent
    data object AppForegrounded : ConnectionEvent
    data object AppBackgrounded : ConnectionEvent
    data object NetworkAvailable : ConnectionEvent
    data object NetworkLost : ConnectionEvent
    data object NetworkPathChanged : ConnectionEvent
    data class RouteEvidenceUpdated(val route: ConnectionRoute) : ConnectionEvent
    data class RouteHealthUpdated(val revision: Long) : ConnectionEvent
    data class SseConnected(val route: ConnectionRoute) : ConnectionEvent
    data class SseEventReceived(val route: ConnectionRoute) : ConnectionEvent
    data class SseStreamClosed(val route: ConnectionRoute) : ConnectionEvent
    data class SseFailed(val route: ConnectionRoute, val message: String?) : ConnectionEvent
    data object BackoffExpired : ConnectionEvent
}
