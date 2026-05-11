package com.openmate.core.data.fake

import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeServerProfileRepository : ServerProfileRepository {
    private val profiles = mutableListOf<ServerProfile>()
    private val profilesFlow = MutableStateFlow<List<ServerProfile>>(emptyList())

    override fun observeAll(): Flow<List<ServerProfile>> = profilesFlow.asStateFlow()

    override suspend fun getAll(): List<ServerProfile> = profiles.toList()

    override suspend fun getById(id: String): ServerProfile? = profiles.find { it.id == id }

    override suspend fun save(profile: ServerProfile) {
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
        profilesFlow.value = profiles.toList()
    }

    override suspend fun delete(id: String) {
        profiles.removeAll { it.id == id }
        profilesFlow.value = profiles.toList()
    }
}
