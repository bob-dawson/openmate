package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionPhase

object ConnectionReducer {
    data class Result(
        val nextState: ConnectionMachineState,
        val actions: List<ConnectionAction>,
    )

    fun reduce(state: ConnectionMachineState, event: ConnectionEvent): Result {
        return when (event) {
            ConnectionEvent.UserDisconnect -> Result(
                nextState = ConnectionMachineState(
                    desiredProfileId = null,
                    activeRoute = null,
                    phase = ConnectionPhase.DISCONNECTED,
                    recoveryGeneration = state.recoveryGeneration,
                ),
                actions = listOf(ConnectionAction.StopActiveTransport),
            )

            is ConnectionEvent.UserConnect -> Result(
                nextState = ConnectionMachineState(
                    desiredProfileId = event.profile.id,
                    activeRoute = null,
                    phase = ConnectionPhase.EVALUATING,
                    recoveryGeneration = state.recoveryGeneration + 1,
                ),
                actions = listOf(ConnectionAction.ReevaluateRoutes),
            )

            ConnectionEvent.UserRetry,
            ConnectionEvent.NetworkAvailable,
            ConnectionEvent.NetworkPathChanged,
            ConnectionEvent.AppForegrounded -> Result(
                nextState = state.copy(
                    phase = ConnectionPhase.EVALUATING,
                    recoveryGeneration = state.recoveryGeneration + 1,
                ),
                actions = listOf(ConnectionAction.ReevaluateRoutes),
            )

            is ConnectionEvent.RepairCompleted -> {
                if (state.desiredProfileId == event.profileId) {
                    Result(
                        nextState = state.copy(
                            phase = ConnectionPhase.EVALUATING,
                            recoveryGeneration = state.recoveryGeneration + 1,
                        ),
                        actions = listOf(ConnectionAction.ReevaluateRoutes),
                    )
                } else {
                    Result(state, emptyList())
                }
            }

            is ConnectionEvent.SseConnected -> Result(
                nextState = state.copy(
                    activeRoute = event.route,
                    phase = ConnectionPhase.CONNECTED,
                ),
                actions = listOf(ConnectionAction.RefreshSessionStatuses),
            )

            is ConnectionEvent.SseEventReceived -> Result(state.copy(activeRoute = event.route), emptyList())

            is ConnectionEvent.SseFailed,
            is ConnectionEvent.SseStreamClosed,
            ConnectionEvent.NetworkLost -> Result(
                nextState = state.copy(phase = ConnectionPhase.RECOVERING),
                actions = listOf(ConnectionAction.StartBackoff),
            )

            else -> Result(state, emptyList())
        }
    }
}
