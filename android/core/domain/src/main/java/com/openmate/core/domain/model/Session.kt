package com.openmate.core.domain.model

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
)
