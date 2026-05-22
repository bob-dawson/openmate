package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ApiRouteResult {
    data class Success(val route: ConnectionRoute) : ApiRouteResult
    data class NetworkFailure(val route: ConnectionRoute, val message: String?) : ApiRouteResult
}

@Singleton
class RouteEvidenceReporter @Inject constructor() {
    private val _events = MutableSharedFlow<ApiRouteResult>(extraBufferCapacity = 64)
    val events: SharedFlow<ApiRouteResult> = _events

    fun reportSuccess(route: ConnectionRoute) {
        _events.tryEmit(ApiRouteResult.Success(route))
    }

    fun reportNetworkFailure(route: ConnectionRoute, message: String?) {
        _events.tryEmit(ApiRouteResult.NetworkFailure(route, message))
    }
}
