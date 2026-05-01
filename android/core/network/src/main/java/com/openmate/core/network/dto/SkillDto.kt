package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class SkillInfoDto(
    val name: String,
    val description: String,
    val location: String = "",
    val content: String = "",
)
