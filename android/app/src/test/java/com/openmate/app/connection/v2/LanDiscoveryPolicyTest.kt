package com.openmate.app.connection.v2

import com.google.common.truth.Truth.assertThat
import com.openmate.core.data.LanDiscoveryStateStore
import org.junit.Test

class LanDiscoveryPolicyTest {

    private fun state(
        fingerprint: String = "",
        failures: Int = 0,
        lastProbeAt: Long = 0L,
        lastGatewaySeenAt: Long = 0L,
    ) = LanDiscoveryStateStore.State(
        fingerprint = fingerprint,
        failures = failures,
        lastProbeAt = lastProbeAt,
        lastGatewaySeenAt = lastGatewaySeenAt,
    )

    private val now = 1_800_000_000_000L

    @Test
    fun sameNetworkAndRecentProbe_doesNotProbe() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(
                fingerprint = "wifi:wlan0:192.168.5.11/24",
                failures = 1,
                lastProbeAt = now - 10_000,
                lastGatewaySeenAt = now - 5_000,
            ),
            now = now,
            fingerprint = "wifi:wlan0:192.168.5.11/24",
            intervalMs = 60_000,
        )

        assertThat(decision.reset).isFalse()
        assertThat(decision.shouldProbe).isFalse()
    }

    @Test
    fun networkChanged_resetsAndProbesImmediately() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(
                fingerprint = "wifi:wlan0:192.168.5.11/24",
                failures = 3,
                lastProbeAt = now - 10_000,
                lastGatewaySeenAt = now - 5_000,
            ),
            now = now,
            fingerprint = "wifi:wlan0:10.20.3.77/16",
            intervalMs = 480_000,
        )

        assertThat(decision.envChanged).isTrue()
        assertThat(decision.reset).isTrue()
        assertThat(decision.shouldProbe).isTrue()
    }

    @Test
    fun gatewayRecoveredAfterLongOffline_resetsAndProbes() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(
                fingerprint = "wifi:wlan0:192.168.5.11/24",
                failures = 4,
                lastProbeAt = now - 10_000,
                lastGatewaySeenAt = now - 120_000,
            ),
            now = now,
            fingerprint = "wifi:wlan0:192.168.5.11/24",
            intervalMs = 900_000,
        )

        assertThat(decision.recoveredAfterOffline).isTrue()
        assertThat(decision.shouldProbe).isTrue()
    }

    @Test
    fun shortGatewayGap_doesNotReset() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(
                fingerprint = "wifi:wlan0:192.168.5.11/24",
                failures = 2,
                lastProbeAt = now - 10_000,
                lastGatewaySeenAt = now - 5_000,
            ),
            now = now,
            fingerprint = "wifi:wlan0:192.168.5.11/24",
            intervalMs = 240_000,
        )

        assertThat(decision.recoveredAfterOffline).isFalse()
        assertThat(decision.reset).isFalse()
        assertThat(decision.shouldProbe).isFalse()
    }

    @Test
    fun intervalExpired_probesWithoutReset() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(
                fingerprint = "wifi:wlan0:192.168.5.11/24",
                failures = 2,
                lastProbeAt = now - 240_000,
                lastGatewaySeenAt = now - 5_000,
            ),
            now = now,
            fingerprint = "wifi:wlan0:192.168.5.11/24",
            intervalMs = 240_000,
        )

        assertThat(decision.reset).isFalse()
        assertThat(decision.due).isTrue()
        assertThat(decision.shouldProbe).isTrue()
    }

    @Test
    fun firstRun_probesImmediately() {
        val decision = LanDiscoveryPolicy.decide(
            state = state(),
            now = now,
            fingerprint = "wifi:wlan0:192.168.5.11/24",
            intervalMs = 60_000,
        )

        assertThat(decision.envChanged).isFalse()
        assertThat(decision.shouldProbe).isTrue()
    }

    @Test
    fun nextFailures_incrementsAndResetsOnCandidateChange() {
        assertThat(LanDiscoveryPolicy.nextFailures(0, candidatesChanged = false)).isEqualTo(1)
        assertThat(LanDiscoveryPolicy.nextFailures(3, candidatesChanged = false)).isEqualTo(4)
        assertThat(LanDiscoveryPolicy.nextFailures(4, candidatesChanged = false)).isEqualTo(4)
        assertThat(LanDiscoveryPolicy.nextFailures(4, candidatesChanged = true)).isEqualTo(0)
    }
}
