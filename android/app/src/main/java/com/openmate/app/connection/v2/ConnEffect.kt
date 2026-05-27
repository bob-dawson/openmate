package com.openmate.app.connection.v2

import com.openmate.core.domain.model.ServerProfile

sealed class ConnEffect {
    data class CheckNetwork(val profile: ServerProfile, val attempt: Int) : ConnEffect()
    data object ProbeGateway : ConnEffect()
    data object ProbeDirect : ConnEffect()
    data object StartSse : ConnEffect()
    data object StopSse : ConnEffect()
    data class StartBackoff(val delayMs: Long) : ConnEffect()
    data object StopBackoff : ConnEffect()
    data object RefreshSessions : ConnEffect()
    data class UpdateLastConnectedAt(val profileId: String) : ConnEffect()
    data object StartDirectCheckLoop : ConnEffect()
    data object StopDirectCheckLoop : ConnEffect()
    data object RestartDirectCheckLoop : ConnEffect()
}