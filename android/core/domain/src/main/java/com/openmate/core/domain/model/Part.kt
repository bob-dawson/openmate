package com.openmate.core.domain.model

sealed interface Part {

    data class TextPart(
        val text: String,
        val synthetic: Boolean = false,
        val ignored: Boolean = false,
    ) : Part

    data class ToolInvocationPart(
        val toolCallID: String,
        val toolName: String,
        val state: ToolCallState,
        val args: String? = null,
        val result: String? = null,
    ) : Part

    data class StepStartPart(val snapshot: String? = null) : Part

    data class StepFinishPart(
        val reason: String = "",
        val snapshot: String? = null,
        val cost: Double = 0.0,
        val tokens: TokenUsage? = null,
    ) : Part

    data class ReasoningPart(val text: String) : Part

    data class FilePart(
        val mime: String,
        val url: String,
        val filename: String? = null,
    ) : Part

    data class SnapshotPart(val snapshot: String) : Part

    data class PatchPart(
        val hash: String,
        val files: List<String>,
    ) : Part

    data class AgentPart(val name: String) : Part

    data class CompactionPart(
        val auto: Boolean,
        val overflow: Boolean = false,
    ) : Part

    data class SubtaskPart(
        val prompt: String,
        val description: String,
        val agent: String,
    ) : Part

    data class RetryPart(
        val attempt: Int,
        val error: String? = null,
    ) : Part
}

data class TokenUsage(
    val total: Long? = null,
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
)

enum class ToolCallState {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
}
