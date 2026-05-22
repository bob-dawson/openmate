package com.openmate.core.network

sealed interface SyncSseSignal {
    val routeBaseUrl: String

    data class ConnectStarted(override val routeBaseUrl: String) : SyncSseSignal
    data class Connected(override val routeBaseUrl: String) : SyncSseSignal
    data class EventReceived(override val routeBaseUrl: String) : SyncSseSignal
    data class StreamClosed(override val routeBaseUrl: String) : SyncSseSignal
    data class Failed(override val routeBaseUrl: String, val message: String?) : SyncSseSignal
}
