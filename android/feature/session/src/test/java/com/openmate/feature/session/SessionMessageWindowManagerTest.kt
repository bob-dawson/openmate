package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncChange
import org.junit.Test

class SessionMessageWindowManagerTest {

    @Test
    fun applyInsert_appendsNewTailMessage() {
        val state = SessionMessageWindowManager.State(
            messages = listOf(msg("m1", 1), msg("m2", 2)),
            loadedCount = 30,
            hasOlderMessages = true,
        )

        val updated = SessionMessageWindowManager.apply(
            state,
            listOf(SessionMessageSyncChange.Insert(msg("m3", 3))),
        )

        assertThat(updated.messages.map { it.id }).containsExactly("m1", "m2", "m3").inOrder()
    }

    @Test
    fun applyUpdate_replacesMessageInWindowById() {
        val state = SessionMessageWindowManager.State(
            messages = listOf(msg("m1", 1), msg("m2", 2, data = "old")),
            loadedCount = 30,
            hasOlderMessages = false,
        )

        val updated = SessionMessageWindowManager.apply(
            state,
            listOf(SessionMessageSyncChange.Update(msg("m2", 2, data = "new"))),
        )

        assertThat(updated.messages.single { it.id == "m2" }.data).isEqualTo("new")
    }

    @Test
    fun applyRemove_dropsMessageFromWindowById() {
        val state = SessionMessageWindowManager.State(
            messages = listOf(msg("m1", 1), msg("m2", 2), msg("m3", 3)),
            loadedCount = 30,
            hasOlderMessages = true,
        )

        val updated = SessionMessageWindowManager.apply(
            state,
            listOf(SessionMessageSyncChange.Remove("m2")),
        )

        assertThat(updated.messages.map { it.id }).containsExactly("m1", "m3").inOrder()
    }

    @Test
    fun prependOlderPage_prependsOlderMessagesAndUpdatesPagingMetadata() {
        val state = SessionMessageWindowManager.State(
            messages = listOf(msg("m3", 3), msg("m4", 4)),
            loadedCount = 2,
            hasOlderMessages = true,
        )

        val updated = SessionMessageWindowManager.prependOlderPage(
            state = state,
            olderPage = listOf(msg("m1", 1), msg("m2", 2), msg("m3", 3, data = "older-duplicate")),
            hasOlderMessages = false,
        )

        assertThat(updated.messages.map { it.id }).containsExactly("m1", "m2", "m3", "m4").inOrder()
        assertThat(updated.loadedCount).isEqualTo(4)
        assertThat(updated.hasOlderMessages).isFalse()
    }

    private fun msg(id: String, timeCreated: Long, data: String = "{}") =
        SessionMessage(
            id = id,
            sessionId = "session",
            type = "assistant",
            data = data,
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
        )
}
