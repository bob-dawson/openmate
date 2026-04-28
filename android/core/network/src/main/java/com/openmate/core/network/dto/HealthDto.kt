package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val healthy: Boolean = false,
    val version: String = "",
)
