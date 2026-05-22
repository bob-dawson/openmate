package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute

data class ConnectionMachineState(
    val desiredProfileId: String?,
    val activeRoute: ConnectionRoute?,
    val phase: ConnectionPhase,
    val recoveryGeneration: Long,
)
