package com.openmate.core.domain.repository

import com.openmate.core.domain.model.CachedFile

interface FileCacheRepository {
    suspend fun getCachedFile(remotePath: String, profileId: String): CachedFile?
    suspend fun saveCachedFile(entity: CachedFile)
    suspend fun clearAllCache(): Long
    suspend fun totalCacheSize(): Long
    suspend fun getAllCachedFiles(): List<CachedFile>
    suspend fun deleteCachedFile(entity: CachedFile)
}
