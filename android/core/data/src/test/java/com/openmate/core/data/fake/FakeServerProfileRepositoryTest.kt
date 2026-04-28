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
        val profile = ServerProfile("1", "Work", "1.1.1.1", 4096, null, 100L)
        repo.save(profile)
        assertThat(repo.getAll()).containsExactly(profile)
    }

    @Test
    fun save_updatesExisting() = runTest {
        repo.save(ServerProfile("1", "Old", "1.1.1.1", 4096, null, 100L))
        repo.save(ServerProfile("1", "New", "2.2.2.2", 8080, null, 100L))
        assertThat(repo.getAll()).hasSize(1)
        assertThat(repo.getById("1")!!.name).isEqualTo("New")
    }

    @Test
    fun delete() = runTest {
        repo.save(ServerProfile("1", "A", "1.1.1.1", 4096, null, 100L))
        repo.delete("1")
        assertThat(repo.getAll()).isEmpty()
    }

    @Test
    fun getById_notFound() = runTest {
        assertThat(repo.getById("missing")).isNull()
    }
}
