package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class PathInfo(
    val directory: String = "",
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
)
