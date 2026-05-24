package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class McpServerEntry(
    val name: String,
    val status: String,
    val error: String? = null,
)
