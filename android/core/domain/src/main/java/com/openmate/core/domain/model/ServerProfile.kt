package com.openmate.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 4097,
    val bridgeId: String = "",
    val instanceId: String = "",
    val password: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null,
    val gatewayEnabled: Boolean = true,
)
