package com.openmate.core.domain.model

sealed interface ConnectionRoute {
    data class Direct(val address: String, val port: Int) : ConnectionRoute
    data class Gateway(val instanceId: String) : ConnectionRoute
}
