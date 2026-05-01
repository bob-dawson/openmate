package com.openmate.core.database.entity

import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.ToolCallState
import kotlinx.serialization.json.JsonObject

data class MetadataTuple(
    val id: String,
    val toolMetadata: String?,
)

data class PartLiteEntity(
    val id: String,
    val messageID: String,
    val sessionID: String,
    val type: String,
    val sequence: Int,
    val text: String? = null,
    val toolCallID: String? = null,
    val toolName: String? = null,
    val toolState: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val snapshot: String? = null,
    val hash: String? = null,
    val files: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val name: String? = null,
    val reason: String? = null,
    val cost: Double? = null,
    val agent: String? = null,
    val auto: Boolean? = null,
    val overflow: Boolean? = null,
    val prompt: String? = null,
    val description: String? = null,
    val attempt: Int? = null,
    val error: String? = null,
)

fun PartLiteEntity.toDomain(): Part {
    return toDomainWithMetadata(null)
}

fun PartLiteEntity.toDomainWithMetadata(metadata: JsonObject?): Part {
    return when (type) {
        "text" -> Part.TextPart(id = id, text = text ?: "")
        "tool" -> Part.ToolInvocationPart(
            id = id,
            toolCallID = toolCallID ?: "",
            toolName = toolName ?: "",
            state = toolState?.let { runCatching { ToolCallState.valueOf(it) }.getOrNull() } ?: ToolCallState.PENDING,
            args = toolArgs,
            result = toolResult,
            metadata = metadata,
        )
        "step-start" -> Part.StepStartPart(id = id, snapshot = snapshot)
        "step-finish" -> Part.StepFinishPart(
            id = id,
            reason = reason ?: "",
            snapshot = snapshot,
            cost = cost ?: 0.0,
        )
        "reasoning" -> Part.ReasoningPart(id = id, text = text ?: "")
        "file" -> Part.FilePart(
            id = id,
            mime = mime ?: "",
            url = url ?: "",
            filename = filename,
        )
        "snapshot" -> Part.SnapshotPart(id = id, snapshot = snapshot ?: "")
        "patch" -> Part.PatchPart(id = id, hash = hash ?: "", files = files?.split(",") ?: emptyList())
        "agent" -> Part.AgentPart(id = id, name = name ?: "")
        "compaction" -> Part.CompactionPart(
            id = id,
            auto = auto ?: false,
            overflow = overflow ?: false,
        )
        "subtask" -> Part.SubtaskPart(
            id = id,
            prompt = prompt ?: "",
            description = description ?: "",
            agent = agent ?: "",
        )
        "retry" -> Part.RetryPart(
            id = id,
            attempt = attempt ?: 0,
            error = error,
        )
        else -> Part.TextPart(id = id, text = text ?: "")
    }
}
