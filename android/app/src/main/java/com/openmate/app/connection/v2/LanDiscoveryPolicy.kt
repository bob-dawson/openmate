package com.openmate.app.connection.v2

import com.openmate.core.data.LanDiscoveryStateStore

data class LanDiscoveryDecision(
    val envChanged: Boolean,
    val recoveredAfterOffline: Boolean,
    val due: Boolean,
) {
    val reset: Boolean get() = envChanged || recoveredAfterOffline
    val shouldProbe: Boolean get() = reset || due
}

object LanDiscoveryPolicy {
    const val GATEWAY_RESET_GAP_MS = 60_000L

    fun decide(
        state: LanDiscoveryStateStore.State,
        now: Long,
        fingerprint: String,
        intervalMs: Long,
    ): LanDiscoveryDecision {
        val envChanged = state.fingerprint.isNotEmpty() && state.fingerprint != fingerprint
        val offlineGap = if (state.lastGatewaySeenAt > 0) now - state.lastGatewaySeenAt else 0L
        val recoveredAfterOffline = state.lastGatewaySeenAt > 0 && offlineGap >= GATEWAY_RESET_GAP_MS
        val due = now - state.lastProbeAt >= intervalMs
        return LanDiscoveryDecision(
            envChanged = envChanged,
            recoveredAfterOffline = recoveredAfterOffline,
            due = due,
        )
    }

    fun nextFailures(current: Int, candidatesChanged: Boolean): Int {
        return if (candidatesChanged) 0 else (current + 1).coerceAtMost(MAX_FAILURES)
    }

    const val MAX_FAILURES = 4

    fun intervalMs(failures: Int): Long = LanBackoff.intervalMs(failures)
}
