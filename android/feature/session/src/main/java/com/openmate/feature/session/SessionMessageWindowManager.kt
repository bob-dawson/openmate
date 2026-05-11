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
                is SessionMessageSyncChange.Insert ->
                    (messages + change.message)
                        .distinctBy(SessionMessage::id)
                        .sortedWith(compareBy(SessionMessage::timeCreated, SessionMessage::id))

                is SessionMessageSyncChange.Update ->
                    messages.map { message ->
                        if (message.id == change.message.id) change.message else message
                    }

                is SessionMessageSyncChange.Remove ->
                    messages.filterNot { message -> message.id == change.messageId }
            }
        }

        return state.copy(messages = messages)
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
