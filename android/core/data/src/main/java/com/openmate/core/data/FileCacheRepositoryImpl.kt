package com.openmate.core.data

import com.openmate.core.database.CacheDatabase
import com.openmate.core.database.entity.CachedFileEntity
import com.openmate.core.domain.model.CachedFile
import com.openmate.core.domain.repository.FileCacheRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileCacheRepositoryImpl @Inject constructor(
    private val cacheDatabase: CacheDatabase,
) : FileCacheRepository {

    private val dao get() = cacheDatabase.cachedFileDao()

    private fun CachedFileEntity.toDomain() = CachedFile(
        remotePath = remotePath,
        localPath = localPath,
        filename = filename,
        fileSize = fileSize,
        modifiedTime = modifiedTime,
        profileId = profileId,
        cachedAt = cachedAt,
    )

    private fun CachedFile.toEntity() = CachedFileEntity(
        remotePath = remotePath,
        localPath = localPath,
        filename = filename,
        fileSize = fileSize,
        modifiedTime = modifiedTime,
        profileId = profileId,
        cachedAt = cachedAt,
    )

    override suspend fun getCachedFile(remotePath: String, profileId: String): CachedFile? {
        return dao.getByRemotePath(remotePath, profileId)?.toDomain()
    }

    override suspend fun saveCachedFile(entity: CachedFile) {
        dao.insert(entity.toEntity())
    }

    override suspend fun clearAllCache(): Long {
        val total = dao.totalCacheSize()
        val all = dao.getAll()
        all.forEach { entity ->
            val file = File(entity.localPath)
            if (file.exists()) file.delete()
        }
        dao.deleteAll()
        return total
    }

    override suspend fun totalCacheSize(): Long {
        return dao.totalCacheSize()
    }

    override suspend fun getAllCachedFiles(): List<CachedFile> {
        return dao.getAll().map { it.toDomain() }
    }

    override suspend fun deleteCachedFile(entity: CachedFile) {
        val file = File(entity.localPath)
        if (file.exists()) file.delete()
        dao.delete(entity.toEntity())
    }
}
