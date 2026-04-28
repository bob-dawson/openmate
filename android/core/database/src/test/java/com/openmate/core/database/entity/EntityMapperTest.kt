package com.openmate.core.database.entity

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.*
import org.junit.Test

class EntityMapperTest {

    @Test
    fun sessionEntity_roundTrip() {
        val session = Session(
            id = "s-1",
            title = "Test",
            directory = "/home/project",
            projectID = "p-1",
            workspaceID = "ws-1",
            createdAt = 1700000000000L,
            updatedAt = 1700001000000L,
            status = SessionStatus.BUSY,
        )
        val entity = session.toEntity()
        val domain = entity.toDomain()
        assertThat(domain).isEqualTo(session)
    }

    @Test
    fun sessionEntity_nullFields() {
        val session = Session(
            id = "s-2",
            title = "T",
            directory = "/d",
            projectID = "p",
            createdAt = 100L,
            updatedAt = 100L,
        )
        val entity = session.toEntity()
        assertThat(entity.workspaceID).isNull()
        assertThat(entity.status).isNull()
        val domain = entity.toDomain()
        assertThat(domain.workspaceID).isNull()
        assertThat(domain.status).isNull()
    }

    @Test
    fun messageEntity_toDomain() {
        val entity = MessageEntity(
            id = "m-1",
            sessionID = "s-1",
            role = "USER",
            agent = null,
            createdAt = 100L,
        )
        val parts = listOf(Part.TextPart("Hello"))
        val domain = entity.toDomain(parts)
        assertThat(domain.id).isEqualTo("m-1")
        assertThat(domain.role).isEqualTo(MessageRole.USER)
        assertThat(domain.parts).hasSize(1)
    }

    @Test
    fun message_toEntity() {
        val msg = Message(
            id = "m-1",
            sessionID = "s-1",
            role = MessageRole.ASSISTANT,
            agent = "coder",
            createdAt = 200L,
        )
        val entity = msg.toEntity()
        assertThat(entity.role).isEqualTo("ASSISTANT")
        assertThat(entity.agent).isEqualTo("coder")
    }

    @Test
    fun partEntity_textPart_roundTrip() {
        val part = Part.TextPart("Hello")
        val entity = part.toEntity("m-1", "s-1", 0)
        assertThat(entity.type).isEqualTo("text")
        assertThat(entity.text).isEqualTo("Hello")
        val domain = entity.toDomain()
        assertThat(domain).isInstanceOf(Part.TextPart::class.java)
        assertThat((domain as Part.TextPart).text).isEqualTo("Hello")
    }

    @Test
    fun partEntity_toolInvocation_roundTrip() {
        val part = Part.ToolInvocationPart(
            toolCallID = "tc-1",
            toolName = "bash",
            state = ToolCallState.RESULT,
            args = """{"cmd":"ls"}""",
            result = "out",
        )
        val entity = part.toEntity("m-1", "s-1", 1)
        assertThat(entity.type).isEqualTo("tool-invocation")
        assertThat(entity.toolState).isEqualTo("RESULT")
        val domain = entity.toDomain() as Part.ToolInvocationPart
        assertThat(domain.toolCallID).isEqualTo("tc-1")
        assertThat(domain.state).isEqualTo(ToolCallState.RESULT)
    }

    @Test
    fun partEntity_allTypes_roundTrip() {
        val parts = listOf<Part>(
            Part.TextPart("hi"),
            Part.StepStartPart("search"),
            Part.StepFinishPart("search"),
            Part.ReasoningPart("hmm"),
            Part.FilePart("/a.kt", "code"),
            Part.SnapshotPart("/b.kt", "snap"),
            Part.PatchPart("/c.kt", "@@ -1 +1 @@"),
            Part.AgentPart("coder"),
            Part.CompactionPart("summary"),
            Part.SubtaskPart("s-sub"),
        )
        parts.forEachIndexed { idx, part ->
            val entity = part.toEntity("m-1", "s-1", idx)
            val domain = entity.toDomain()
            assertThat(domain).isNotNull()
        }
    }

    @Test
    fun permissionEntity_roundTrip() {
        val req = PermissionRequest(
            id = "p-1",
            sessionID = "s-1",
            toolName = "bash",
            input = """{"cmd":"rm"}""",
            createdAt = 100L,
        )
        val entity = req.toEntity()
        val domain = entity.toDomain()
        assertThat(domain).isEqualTo(req)
    }

    @Test
    fun questionEntity_roundTrip() {
        val req = QuestionRequest(
            id = "q-1",
            sessionID = "s-1",
            questions = listOf(
                QuestionItem("Choose", listOf("A", "B")),
            ),
        )
        val entity = req.toEntity()
        val domain = entity.toDomain()
        assertThat(domain.id).isEqualTo("q-1")
        assertThat(domain.sessionID).isEqualTo("s-1")
        assertThat(domain.questions).hasSize(1)
        assertThat(domain.questions[0].label).isEqualTo("Choose")
        assertThat(domain.questions[0].options).containsExactly("A", "B")
    }
}
