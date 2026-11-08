package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.SessionMessage
import org.junit.Test

class SessionMessageCompletionTest {

    private fun message(
        type: String,
        data: String,
        completedAt: Long? = null,
        timeCreated: Long = 1000L,
    ) = SessionMessage(
        id = "msg-1",
        sessionId = "ses-1",
        type = type,
        data = data,
        timeCreated = timeCreated,
        timeUpdated = 2000L,
        completedAt = completedAt,
        roundMark = true,
    )

    @Test
    fun compactionWithSummaryButNoCompletedAt_isTreatedAsCompletedAtCreatedTime() {
        val msg = message(
            type = "compaction",
            data = """{"status":"completed","summary":"summary text","recent":"","time":{"created":1000}}""",
        )

        assertThat(SessionMessageCompletion.displayCompletedAt(msg)).isEqualTo(1000L)
    }

    @Test
    fun compactionWithContentButNoStatus_isTreatedAsCompleted() {
        val msg = message(
            type = "compaction",
            data = """{"summary":"legacy summary","time":{"created":1000}}""",
        )

        assertThat(SessionMessageCompletion.displayCompletedAt(msg)).isEqualTo(1000L)
    }

    @Test
    fun runningCompactionWithoutContent_keepsRunning() {
        val msg = message(
            type = "compaction",
            data = """{"status":"running","summary":"","recent":"","time":{"created":1000}}""",
        )

        assertThat(SessionMessageCompletion.displayCompletedAt(msg)).isNull()
    }

    @Test
    fun existingCompletedAt_wins() {
        val msg = message(
            type = "compaction",
            data = """{"status":"completed","summary":"s"}""",
            completedAt = 1500L,
        )

        assertThat(SessionMessageCompletion.displayCompletedAt(msg)).isEqualTo(1500L)
    }

    @Test
    fun assistantWithoutCompletedAt_isNotAffected() {
        val msg = message(
            type = "assistant",
            data = """{"finish":null,"agent":"build"}""",
        )

        assertThat(SessionMessageCompletion.displayCompletedAt(msg)).isNull()
    }

    @Test
    fun malformedData_doesNotThrow() {
        assertThat(SessionMessageCompletion.parse("not-json")).isNull()
        assertThat(SessionMessageCompletion.parse("""{"status":"completed"}""")).isNotNull()
        assertThat(
            SessionMessageCompletion.displayCompletedAt(
                null, "compaction", null, 1000L,
            ),
        ).isNull()
    }
}
