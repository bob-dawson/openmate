package com.openmate.core.domain.model

data class Workspace(
    val directory: String,
    val sessionCount: Int,
    val latestUpdatedAt: Long,
    val latestTitle: String,
)
