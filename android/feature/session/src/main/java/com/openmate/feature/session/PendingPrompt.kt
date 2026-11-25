package com.openmate.feature.session

import com.openmate.core.network.dto.SessionInboxItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How opencode delivers a prompt that arrived while the agent is working. */
enum class PromptDelivery(val wire: String) {
    /** Insert before the agent's next model call, even mid-work. */
    STEER("steer"),

    /** Wait until the agent finishes its current work, then deliver. */
    QUEUE("queue"),
    ;

    companion object {
        fun fromWire(value: String?): PromptDelivery =
            if (value == QUEUE.wire) QUEUE else STEER
    }
}

/**
 * A prompt that is enqueued but not yet admitted into the transcript.
 *
 * These are shown above the input instead of the timeline: their enqueue time is not their
 * processing time, and they cannot be reverted until opencode delivers them.
 */
data class PendingPrompt(
    val id: String,
    val text: String,
    val delivery: PromptDelivery,
    val createdAt: Long,
    val imageCount: Int,
    val fileCount: Int,
    /** True when only this client knows about it yet (send response not reflected by inbox yet). */
    val localOnly: Boolean = false,
) {
    val hasAttachments: Boolean get() = imageCount + fileCount > 0
}

internal fun SessionInboxItemDto.toPendingPrompt(): PendingPrompt? {
    if (type != "user") return null
    val body = payload ?: return null
    val text = body["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    var images = 0
    var files = 0
    body["files"]?.jsonArray?.forEach { element ->
        val file = element as? JsonObject ?: return@forEach
        val mime = file["mime"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (mime.startsWith("image/")) images++ else files++
    }
    if (text.isBlank() && images + files == 0) return null
    return PendingPrompt(
        id = id,
        text = text,
        delivery = PromptDelivery.fromWire(delivery),
        createdAt = time?.created ?: 0L,
        imageCount = images,
        fileCount = files,
    )
}
