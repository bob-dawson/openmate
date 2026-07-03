package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class EventReplayerTest {

    @Test
    fun processEvent_messageRemoved_returnsDelete() = runTest {
        val replayer = EventReplayer()
        val event = ReplayEvent(
            id = "evt-1",
            type = "message.removed",
            data = buildJsonObject {
                put("messageID", JsonPrimitive("msg-123"))
            },
        )
        val changes = replayer.processEvent(event, "session-1")
        assertThat(changes).hasSize(1)
        val delete = changes.first() as ReplayChange.Delete
        assertThat(delete.id).isEqualTo("msg-123")
    }

    @Test
    fun processEvent_unknownType_returnsEmpty() = runTest {
        val replayer = EventReplayer()
        val event = ReplayEvent(
            id = "evt-1",
            type = "session.next.step.started",
            data = buildJsonObject {},
        )
        val changes = replayer.processEvent(event, "session-1")
        assertThat(changes).isEmpty()
    }

    @Test
    fun processEvent_messageRemoved_missingMessageID_returnsEmpty() = runTest {
        val replayer = EventReplayer()
        val event = ReplayEvent(
            id = "evt-1",
            type = "message.removed",
            data = buildJsonObject {},
        )
        val changes = replayer.processEvent(event, "session-1")
        assertThat(changes).isEmpty()
    }
}
