package com.openmate.app.connection.v2

import com.openmate.core.domain.model.ServerProfile

sealed class ConnEffect {
    data class ProbeGateway(val instanceId: String) : ConnEffect()
    data class ProbeDirect(val address: String, val port: Int) : ConnEffect()
    data class StartSse(val baseUrl: String, val instanceId: String?) : ConnEffect()
    data object StopSse : ConnEffect()
    data class StartBackoff(val delayMs: Long) : ConnEffect()
    data object StopBackoff : ConnEffect()
    data class SetApiClient(val baseUrl: String, val instanceId: String?) : ConnEffect()
    data object ClearApiClient : ConnEffect()
    data object RefreshSessions : ConnEffect()
    data class SaveProfile(val profile: ServerProfile) : ConnEffect()
    data class StartDirectCheckLoop(val address: String, val port: Int) : ConnEffect()
    data object StopDirectCheckLoop : ConnEffect()
}
