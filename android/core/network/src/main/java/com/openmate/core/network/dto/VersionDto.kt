package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class VersionManifest(
    val android: ModuleVersion? = null,
    val bridge: ModuleVersion? = null,
)

@Serializable
data class ModuleVersion(
    val version: String,
    val tag: String,
    val releasedAt: String? = null,
)
