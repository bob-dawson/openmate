package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionRequestTest {
    @Test
    fun create() {
        val req = PermissionRequest(
            id = "p-1",
            sessionID = "s-1",
            toolName = "bash",
            input = """{"command":"rm -rf /"}""",
            createdAt = 1700000000000L,
        )
        assertThat(req.id).isEqualTo("p-1")
        assertThat(req.toolName).isEqualTo("bash")
    }

    @Test
    fun permissionReply_values() {
        assertThat(PermissionReply.values()).asList()
            .containsExactly(PermissionReply.ALLOW, PermissionReply.DENY)
    }
}
