package com.openmate.core.domain.model

data class CachedFile(
    val remotePath: String,
    val localPath: String,
    val filename: String,
    val fileSize: Long,
    val modifiedTime: Long,
    val profileId: String,
    val cachedAt: Long = System.currentTimeMillis(),
)
