package com.openmate.core.domain.repository

import com.openmate.core.domain.model.ServerProfile
import kotlinx.coroutines.flow.Flow

interface ServerProfileRepository {
    fun observeAll(): Flow<List<ServerProfile>>
    suspend fun getAll(): List<ServerProfile>
    suspend fun getById(id: String): ServerProfile?
    suspend fun save(profile: ServerProfile)
    suspend fun delete(id: String)
}
