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
                ConnectionStatus.ERROR,
                ConnectionStatus.NOT_BRIDGE,
            )
    }
}
