package com.openmate.core.data.fake

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.ServerProfile
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FakeServerProfileRepositoryTest {
    private lateinit var repo: FakeServerProfileRepository

    @Before
    fun setup() {
        repo = FakeServerProfileRepository()
    }

    @Test
    fun save_andGetAll() = runTest {
        val profile = ServerProfile(id = "1", name = "Work", address = "1.1.1.1", port = 4096, password = null, createdAt = 100L)
        repo.save(profile)
        assertThat(repo.getAll()).containsExactly(profile)
    }

    @Test
    fun save_updatesExisting() = runTest {
        repo.save(ServerProfile(id = "1", name = "Old", address = "1.1.1.1", port = 4096, password = null, createdAt = 100L))
        repo.save(ServerProfile(id = "1", name = "New", address = "2.2.2.2", port = 8080, password = null, createdAt = 100L))
        assertThat(repo.getAll()).hasSize(1)
        assertThat(repo.getById("1")!!.name).isEqualTo("New")
    }

    @Test
    fun delete() = runTest {
        repo.save(ServerProfile(id = "1", name = "A", address = "1.1.1.1", port = 4096, password = null, createdAt = 100L))
        repo.delete("1")
        assertThat(repo.getAll()).isEmpty()
    }

    @Test
    fun getById_notFound() = runTest {
        assertThat(repo.getById("missing")).isNull()
    }
}
