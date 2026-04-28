package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartTest {
    @Test
    fun textPart() {
        val part = Part.TextPart("Hello world")
        assertThat(part.text).isEqualTo("Hello world")
    }

    @Test
    fun toolInvocationPart() {
        val part = Part.ToolInvocationPart(
            toolCallID = "tc-1",
            toolName = "bash",
            state = ToolCallState.CALL,
            args = """{"command":"ls"}""",
        )
        assertThat(part.toolCallID).isEqualTo("tc-1")
        assertThat(part.state).isEqualTo(ToolCallState.CALL)
        assertThat(part.result).isNull()
    }

    @Test
    fun stepStartPart() {
        val part = Part.StepStartPart("search")
        assertThat(part.stepType).isEqualTo("search")
    }

    @Test
    fun stepFinishPart() {
        val part = Part.StepFinishPart("search")
        assertThat(part.stepType).isEqualTo("search")
    }

    @Test
    fun reasoningPart() {
        val part = Part.ReasoningPart("thinking...")
        assertThat(part.text).isEqualTo("thinking...")
    }

    @Test
    fun filePart() {
        val part = Part.FilePart("/src/Main.kt", "fun main()")
        assertThat(part.filePath).isEqualTo("/src/Main.kt")
        assertThat(part.content).isEqualTo("fun main()")
    }

    @Test
    fun snapshotPart_withoutContent() {
        val part = Part.SnapshotPart("/src/App.kt")
        assertThat(part.filePath).isEqualTo("/src/App.kt")
        assertThat(part.content).isNull()
    }

    @Test
    fun patchPart() {
        val part = Part.PatchPart("/src/Main.kt", "@@ -1 +1 @@")
        assertThat(part.patch).isEqualTo("@@ -1 +1 @@")
    }

    @Test
    fun agentPart() {
        val part = Part.AgentPart("coder")
        assertThat(part.agentName).isEqualTo("coder")
    }

    @Test
    fun compactionPart() {
        val part = Part.CompactionPart("Summary of conversation")
        assertThat(part.summary).isEqualTo("Summary of conversation")
    }

    @Test
    fun subtaskPart() {
        val part = Part.SubtaskPart("s-sub-1")
        assertThat(part.sessionID).isEqualTo("s-sub-1")
    }

    @Test
    fun sealedInterface_polymorphism() {
        val parts: List<Part> = listOf(
            Part.TextPart("hi"),
            Part.ToolInvocationPart("tc-1", "bash", ToolCallState.RESULT),
            Part.ReasoningPart("hmm"),
        )
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).isInstanceOf(Part.TextPart::class.java)
        assertThat(parts[1]).isInstanceOf(Part.ToolInvocationPart::class.java)
        assertThat(parts[2]).isInstanceOf(Part.ReasoningPart::class.java)
    }

    @Test
    fun toolCallState_values() {
        assertThat(ToolCallState.values()).asList()
            .containsExactly(ToolCallState.CALL, ToolCallState.PARTIAL, ToolCallState.RESULT)
    }
}
