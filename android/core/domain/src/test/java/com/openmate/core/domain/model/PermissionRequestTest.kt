package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class PermissionRequestTest {
    @Test
    fun create() {
        val metadata = buildJsonObject {
            put("command", "rm -rf /")
        }
        val req = PermissionRequest(
            id = "p-1",
            sessionID = "s-1",
            permission = "tool.bash",
            patterns = listOf("/tmp/*"),
            metadata = metadata,
            always = listOf("/safe/*"),
            tool = ToolRef(
                messageID = "m-1",
                callID = "call-1",
            ),
        )
        assertThat(req.id).isEqualTo("p-1")
        assertThat(req.permission).isEqualTo("tool.bash")
        assertThat(req.metadata).isEqualTo(metadata)
        assertThat(req.tool).isEqualTo(ToolRef(messageID = "m-1", callID = "call-1"))
    }

    @Test
    fun permissionReply_values() {
        assertThat(PermissionReply.values()).asList()
            .containsExactly(PermissionReply.ONCE, PermissionReply.ALWAYS, PermissionReply.REJECT)
    }
}
