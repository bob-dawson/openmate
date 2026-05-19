package com.openmate.core.domain.model

data class SessionRevert(
    val messageID: String,
    val partID: String? = null,
    val from: String? = null,
    val to: String? = null,
)

data class SessionTokens(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
) {
    val total: Long get() = input + output + reasoning + cacheRead + cacheWrite
}

data class Session(
    val id: String,
    val title: String,
    val directory: String,
    val projectID: String,
    val workspaceID: String? = null,
    val parentID: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isCompacting: Boolean = false,
    val isArchived: Boolean = false,
    val status: SessionStatus? = null,
    val startedAt: Long? = null,
    val phoneStartedAt: Long? = null,
    val totalDuration: Long? = null,
    val modelProviderID: String? = null,
    val modelID: String? = null,
    val modelName: String? = null,
    val revert: SessionRevert? = null,
    val cost: Double = 0.0,
    val tokens: SessionTokens? = null,
    val agent: String? = null,
)
