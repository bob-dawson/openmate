package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute

sealed interface RouteEvidence {
    val route: ConnectionRoute
    val recordedAt: Long

    data class ApiSuccess(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class ApiNetworkFailure(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence

    data class ProbeSuccess(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class ProbeFailure(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence

    data class SsePositive(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class SseSuspicion(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence
}
