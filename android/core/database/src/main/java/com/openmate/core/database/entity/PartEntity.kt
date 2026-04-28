package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.ToolCallState

@Entity(
    tableName = "PartEntity",
    indices = [
        Index("messageID"),
        Index("sessionID"),
        Index("sequence"),
    ],
)
data class PartEntity(
    @PrimaryKey val id: String,
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

fun PartEntity.toDomain(): Part {
    return when (type) {
        "text" -> Part.TextPart(id = id, text = text ?: "")
        "tool" -> Part.ToolInvocationPart(
            id = id,
            toolCallID = toolCallID ?: "",
            toolName = toolName ?: "",
            state = toolState?.let { runCatching { ToolCallState.valueOf(it) }.getOrNull() } ?: ToolCallState.PENDING,
            args = toolArgs,
            result = toolResult,
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

fun Part.toEntity(messageID: String, sessionID: String, sequence: Int): PartEntity {
    val data = when (this) {
        is Part.TextPart -> PartTuple(type = "text", text = text)
        is Part.ToolInvocationPart -> PartTuple(
            type = "tool",
            toolCallID = toolCallID,
            toolName = toolName,
            toolState = state.name,
            toolArgs = args,
            toolResult = result,
        )
        is Part.StepStartPart -> PartTuple(type = "step-start", snapshot = snapshot)
        is Part.StepFinishPart -> PartTuple(
            type = "step-finish",
            reason = reason,
            snapshot = snapshot,
            cost = cost,
        )
        is Part.ReasoningPart -> PartTuple(type = "reasoning", text = text)
        is Part.FilePart -> PartTuple(type = "file", mime = mime, url = url, filename = filename)
        is Part.SnapshotPart -> PartTuple(type = "snapshot", snapshot = snapshot)
        is Part.PatchPart -> PartTuple(type = "patch", hash = hash, files = files.joinToString(","))
        is Part.AgentPart -> PartTuple(type = "agent", name = name)
        is Part.CompactionPart -> PartTuple(type = "compaction", auto = auto, overflow = overflow)
        is Part.SubtaskPart -> PartTuple(type = "subtask", prompt = prompt, description = description, agent = agent)
        is Part.RetryPart -> PartTuple(type = "retry", attempt = attempt, error = error)
    }
    return PartEntity(
        id = id.ifBlank { "${messageID}_part_$sequence" },
        messageID = messageID,
        sessionID = sessionID,
        type = data.type,
        sequence = sequence,
        text = data.text,
        toolCallID = data.toolCallID,
        toolName = data.toolName,
        toolState = data.toolState,
        toolArgs = data.toolArgs,
        toolResult = data.toolResult,
        snapshot = data.snapshot,
        hash = data.hash,
        files = data.files,
        mime = data.mime,
        url = data.url,
        filename = data.filename,
        name = data.name,
        reason = data.reason,
        cost = data.cost,
        agent = data.agent,
        auto = data.auto,
        overflow = data.overflow,
        prompt = data.prompt,
        description = data.description,
        attempt = data.attempt,
        error = data.error,
    )
}

private data class PartTuple(
    val type: String,
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
