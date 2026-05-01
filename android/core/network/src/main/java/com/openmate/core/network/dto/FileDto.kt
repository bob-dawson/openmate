package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileNodeDto(
    val name: String,
    val path: String,
    val absolute: String = "",
    val type: String = "file",
    val ignored: Boolean = false,
)
