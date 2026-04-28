package com.openmate.core.data.fake

import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository

class FakeServerProfileRepository : ServerProfileRepository {
    private val profiles = mutableListOf<ServerProfile>()

    override suspend fun getAll(): List<ServerProfile> = profiles.toList()

    override suspend fun getById(id: String): ServerProfile? = profiles.find { it.id == id }

    override suspend fun save(profile: ServerProfile) {
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
    }

    override suspend fun delete(id: String) {
        profiles.removeAll { it.id == id }
    }
}
