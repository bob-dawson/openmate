package com.openmate.app.connection

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.ConnectionRoute
import org.junit.Test

class RouteEvidenceAggregatorTest {
    @Test
    fun directApiSuccess_createsPositiveDirectEvidence() {
        val evidence = RouteEvidence.ApiSuccess(
            route = ConnectionRoute.Direct("127.0.0.1", 4097),
            recordedAt = 100L,
        )

        assertThat(evidence.route).isEqualTo(ConnectionRoute.Direct("127.0.0.1", 4097))
    }

    @Test
    fun recentApiSuccess_outweighsOlderSseSuspicion() {
        val aggregator = RouteEvidenceAggregator(clock = { 1_000L })

        aggregator.record(RouteEvidence.SseSuspicion(ConnectionRoute.Direct("127.0.0.1", 4097), recordedAt = 100L, message = "closed"))
        aggregator.record(RouteEvidence.ApiSuccess(ConnectionRoute.Direct("127.0.0.1", 4097), recordedAt = 200L))

        val snapshot = aggregator.snapshot.value

        assertThat(snapshot.direct.isUsable).isTrue()
        assertThat(snapshot.direct.source).isEqualTo("api")
    }

    @Test
    fun gatewayRequiresBridgeUsabilityEvidence_notJustReachability() {
        val aggregator = RouteEvidenceAggregator(clock = { 1_000L })

        aggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Gateway("iid-1"), recordedAt = 100L))

        val snapshot = aggregator.snapshot.value

        assertThat(snapshot.gateway.isUsable).isTrue()
    }

    @Test
    fun freshApiEvidence_skipsRedundantProbe() {
        val route = ConnectionRoute.Direct("127.0.0.1", 4097)
        val aggregator = RouteEvidenceAggregator(clock = { 1_000L })

        aggregator.record(RouteEvidence.ApiSuccess(route, recordedAt = 990L))

        val shouldProbe = aggregator.shouldProbe(route, freshnessWindowMs = 100L)

        assertThat(shouldProbe).isFalse()
    }
}
