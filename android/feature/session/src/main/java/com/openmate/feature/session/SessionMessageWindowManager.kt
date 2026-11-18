package com.openmate.feature.session

import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncChange

object SessionMessageWindowManager {
    data class State(
        val messages: List<SessionMessage>,
        val loadedCount: Int,
        val hasOlderMessages: Boolean,
    )

    fun apply(state: State, changes: List<SessionMessageSyncChange>): State {
        var messages = state.messages

        for (change in changes) {
            messages = when (change) {
                is SessionMessageSyncChange.Insert -> {
                    val existingIdx = messages.indexOfLast { it.id == change.message.id }
                    if (existingIdx >= 0) {
                        if (existingIdx == messages.lastIndex) {
                            messages.dropLast(1) + change.message
                        } else {
                            messages.subList(0, existingIdx) + change.message + messages.subList(existingIdx + 1, messages.size)
                        }
                    } else {
                        val insertAt = messages.indexOfFirst { existing ->
                            existing.timeCreated > change.message.timeCreated ||
                                (existing.timeCreated == change.message.timeCreated && existing.id > change.message.id)
                        }
                        if (insertAt < 0) {
                            messages + change.message
                        } else {
                            messages.subList(0, insertAt) + change.message + messages.subList(insertAt, messages.size)
                        }
                    }
                }

                is SessionMessageSyncChange.Update -> {
                    val idx = messages.indexOfLast { it.id == change.message.id }
                    if (idx < 0) {
                        messages
                    } else if (idx == messages.lastIndex) {
                        messages.dropLast(1) + change.message
                    } else {
                        messages.subList(0, idx) + change.message + messages.subList(idx + 1, messages.size)
                    }
                }

                is SessionMessageSyncChange.Remove -> {
                    val idx = messages.indexOfLast { it.id == change.messageId }
                    if (idx < 0) {
                        messages
                    } else if (idx == messages.lastIndex) {
                        messages.dropLast(1)
                    } else {
                        messages.subList(0, idx) + messages.subList(idx + 1, messages.size)
                    }
                }
            }
        }

        return state.copy(messages = messages.distinctBy { it.id })
    }

    fun prependOlderPage(state: State, olderPage: List<SessionMessage>, hasOlderMessages: Boolean): State {
        val merged = (olderPage + state.messages)
            .distinctBy(SessionMessage::id)
            .sortedWith(compareBy(SessionMessage::timeCreated, SessionMessage::id))

        return state.copy(
            messages = merged,
            loadedCount = merged.size,
            hasOlderMessages = hasOlderMessages,
        )
    }
}
