package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class LanCandidateDto(
    val ip: String = "",
    val port: Int = 0,
    val scope: String = "other",
)

@Serializable
data class LanCandidatesResponse(
    val port: Int = 0,
    val candidates: List<LanCandidateDto> = emptyList(),
)
