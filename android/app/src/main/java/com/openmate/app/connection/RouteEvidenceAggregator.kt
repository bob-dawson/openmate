package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RouteEvidenceAggregator(
    private val clock: () -> Long,
) {
    private val evidence = mutableMapOf<ConnectionRoute, RouteEvidence>()
    private val _snapshot = MutableStateFlow(
        RouteHealthSnapshot(
            revision = 0L,
            direct = RouteHealth(isUsable = false, lastEvidenceAt = null, source = null),
            gateway = RouteHealth(isUsable = false, lastEvidenceAt = null, source = null),
        )
    )
    val snapshot: StateFlow<RouteHealthSnapshot> = _snapshot

    fun record(item: RouteEvidence) {
        evidence[item.route] = item
        _snapshot.value = derive(_snapshot.value.revision + 1)
    }

    fun shouldProbe(route: ConnectionRoute, freshnessWindowMs: Long): Boolean {
        val last = evidence[route]?.recordedAt ?: return true
        return clock() - last > freshnessWindowMs
    }

    private fun derive(revision: Long): RouteHealthSnapshot {
        val directEvidence = evidence.entries.firstOrNull { it.key is ConnectionRoute.Direct }?.value
        val gatewayEvidence = evidence.entries.firstOrNull { it.key is ConnectionRoute.Gateway }?.value
        return RouteHealthSnapshot(
            revision = revision,
            direct = directEvidence.toRouteHealth(),
            gateway = gatewayEvidence.toRouteHealth(),
        )
    }

    private fun RouteEvidence?.toRouteHealth(): RouteHealth {
        return when (this) {
            is RouteEvidence.ApiSuccess -> RouteHealth(true, recordedAt, "api")
            is RouteEvidence.ProbeSuccess -> RouteHealth(true, recordedAt, "probe")
            is RouteEvidence.SsePositive -> RouteHealth(true, recordedAt, "sse")
            is RouteEvidence.ApiNetworkFailure -> RouteHealth(false, recordedAt, "api-failure")
            is RouteEvidence.ProbeFailure -> RouteHealth(false, recordedAt, "probe-failure")
            is RouteEvidence.SseSuspicion -> RouteHealth(false, recordedAt, "sse-suspicion")
            null -> RouteHealth(false, null, null)
        }
    }
}
