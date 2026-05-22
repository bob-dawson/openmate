package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerProfileTest {
    @Test
    fun create_withRequiredFields() {
        val profile = ServerProfile(
            id = "id-1",
            name = "Work Desktop",
            address = "100.64.0.1",
            port = 4096,
            createdAt = 1700000000000L,
        )
        assertThat(profile.id).isEqualTo("id-1")
        assertThat(profile.name).isEqualTo("Work Desktop")
        assertThat(profile.address).isEqualTo("100.64.0.1")
        assertThat(profile.port).isEqualTo(4096)
        assertThat(profile.password).isNull()
        assertThat(profile.lastConnectedAt).isNull()
    }

    @Test
    fun create_withAllFields() {
        val profile = ServerProfile(
            id = "id-2",
            name = "Home",
            address = "192.168.1.1",
            port = 8080,
            password = "secret",
            createdAt = 1700000000000L,
            lastConnectedAt = 1700001000000L,
        )
        assertThat(profile.password).isEqualTo("secret")
        assertThat(profile.lastConnectedAt).isEqualTo(1700001000000L)
    }

    @Test
    fun equality_sameValues_areEqual() {
        val p1 = ServerProfile(
            id = "a",
            name = "n",
            address = "1.1.1.1",
            port = 4096,
            createdAt = 100L,
        )
        val p2 = ServerProfile(
            id = "a",
            name = "n",
            address = "1.1.1.1",
            port = 4096,
            createdAt = 100L,
        )
        assertThat(p1).isEqualTo(p2)
    }

    @Test
    fun copy_modifiesFields() {
        val original = ServerProfile(
            id = "a",
            name = "n",
            address = "1.1.1.1",
            port = 4096,
            createdAt = 100L,
        )
        val modified = original.copy(name = "Updated", port = 8080)
        assertThat(modified.name).isEqualTo("Updated")
        assertThat(modified.port).isEqualTo(8080)
        assertThat(original.name).isEqualTo("n")
    }
}
