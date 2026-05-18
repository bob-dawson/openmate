package com.openmate.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProviderListDto(
    val all: List<ProviderInfoDto> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class ProviderInfoDto(
    val id: String,
    val name: String,
    val source: String = "",
    val models: Map<String, ModelInfoDto> = emptyMap(),
)

@Serializable
data class ModelLimitDto(
    val context: Long = 0,
    val input: Long? = null,
    val output: Long = 0,
)

@Serializable
data class ModelInfoDto(
    val id: String,
    val providerID: String = "",
    val name: String = "",
    val family: String? = null,
    val status: String = "active",
    val limit: ModelLimitDto? = null,
    val variants: Map<String, JsonObject>? = null,
)
