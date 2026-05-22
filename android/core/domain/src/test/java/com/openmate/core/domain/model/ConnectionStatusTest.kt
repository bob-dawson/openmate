package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun values() {
        assertThat(ConnectionStatus.values()).asList()
            .containsExactly(
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.CONNECTING,
                ConnectionStatus.CONNECTED,
                ConnectionStatus.GATEWAY_CONNECTED,
                ConnectionStatus.ERROR,
                ConnectionStatus.NOT_BRIDGE,
                ConnectionStatus.PAIRING,
            )
    }

    @Test
    fun connectionSnapshot_gatewayUsable_isDegradedNotDisconnected() {
        val snapshot = ConnectionSnapshot(
            phase = ConnectionPhase.CONNECTED,
            activeRoute = ConnectionRoute.Gateway(instanceId = "iid-1"),
            desiredProfileId = "p1",
            isUsable = true,
            needsUserRepair = false,
            message = null,
        )

        assertThat(snapshot.isUsable).isTrue()
        assertThat(snapshot.activeRoute).isEqualTo(ConnectionRoute.Gateway("iid-1"))
    }
}
