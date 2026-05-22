package com.openmate.core.domain.model

data class ConnectionSnapshot(
    val phase: ConnectionPhase,
    val activeRoute: ConnectionRoute?,
    val desiredProfileId: String?,
    val isUsable: Boolean,
    val needsUserRepair: Boolean,
    val message: String?,
)
