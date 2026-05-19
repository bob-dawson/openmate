package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AgentDto(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
)
