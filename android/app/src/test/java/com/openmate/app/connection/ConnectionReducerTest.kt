package com.openmate.app.connection

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute
import org.junit.Test

class ConnectionReducerTest {
    @Test
    fun userRetry_startsNewRecoveryGeneration() {
        val state = ConnectionMachineState(
            desiredProfileId = "p1",
            activeRoute = null,
            phase = ConnectionPhase.RECOVERING,
            recoveryGeneration = 4L,
        )

        val result = ConnectionReducer.reduce(state, ConnectionEvent.UserRetry)

        assertThat(result.nextState.recoveryGeneration).isEqualTo(5L)
        assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.EVALUATING)
    }

    @Test
    fun userDisconnect_movesMachineToDisconnected() {
        val state = ConnectionMachineState(
            desiredProfileId = "p1",
            activeRoute = ConnectionRoute.Direct("127.0.0.1", 4097),
            phase = ConnectionPhase.CONNECTED,
            recoveryGeneration = 2L,
        )

        val result = ConnectionReducer.reduce(state, ConnectionEvent.UserDisconnect)

        assertThat(result.nextState.desiredProfileId).isNull()
        assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.DISCONNECTED)
        assertThat(result.actions).contains(ConnectionAction.StopActiveTransport)
    }

    @Test
    fun networkAvailable_duringRecovery_restartsEvaluationWithNewGeneration() {
        val state = ConnectionMachineState(
            desiredProfileId = "p1",
            activeRoute = null,
            phase = ConnectionPhase.RECOVERING,
            recoveryGeneration = 8L,
        )

        val result = ConnectionReducer.reduce(state, ConnectionEvent.NetworkAvailable)

        assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.EVALUATING)
        assertThat(result.nextState.recoveryGeneration).isEqualTo(9L)
        assertThat(result.actions).contains(ConnectionAction.ReevaluateRoutes)
    }
}
