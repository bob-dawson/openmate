package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.SessionMessage
import org.junit.Test

class SessionBusyTimerCalculatorTest {

    @Test
    fun displayDuration_whenBusy_showsOnlyCurrentRoundElapsed() {
        val duration = SessionBusyTimerCalculator.displayDuration(
            totalDuration = 9_000L,
            currentBusyStart = 2_000L,
            now = 5_500L,
        )

        assertThat(duration).isEqualTo(3_500L)
    }

    @Test
    fun displayDuration_whenIdle_showsAccumulatedDuration() {
        val duration = SessionBusyTimerCalculator.displayDuration(
            totalDuration = 9_000L,
            currentBusyStart = null,
            now = 5_500L,
        )

        assertThat(duration).isEqualTo(9_000L)
    }

    @Test
    fun syncedCurrentRoundUserWithoutAssistantYet_startsFromUserTime() {
        val messages = listOf(
            user(id = "user_old", timeCreated = 1_000L, roundMark = true),
            assistant(id = "assistant_done", timeCreated = 1_100L, completedAt = 1_200L, roundMark = true),
            user(id = "user_current", timeCreated = 2_000L, roundMark = true),
        )

        val start = SessionBusyTimerCalculator.findBusyStart(messages)

        assertThat(start).isEqualTo(2_000L)
    }

    @Test
    fun staleRoundMarkedUserFromCompletedTurn_isIgnored() {
        val messages = listOf(
            user(id = "user_old", timeCreated = 1_000L, roundMark = true),
            assistant(id = "assistant_done", timeCreated = 1_100L, completedAt = 1_200L, roundMark = true),
            assistant(id = "assistant_running", timeCreated = 2_000L, completedAt = null, roundMark = false),
        )

        val start = SessionBusyTimerCalculator.findBusyStart(messages)

        assertThat(start).isNull()
    }

    @Test
    fun latestRoundMarkedUserWithoutCompletedAssistantAfterIt_isUsed() {
        val messages = listOf(
            user(id = "user_old", timeCreated = 1_000L, roundMark = true),
            assistant(id = "assistant_done", timeCreated = 1_100L, completedAt = 1_200L, roundMark = true),
            user(id = "user_current", timeCreated = 2_000L, roundMark = true),
            assistant(id = "assistant_running", timeCreated = 2_100L, completedAt = null, roundMark = false),
        )

        val start = SessionBusyTimerCalculator.findBusyStart(messages)

        assertThat(start).isEqualTo(2_000L)
    }

    private fun user(id: String, timeCreated: Long, roundMark: Boolean) =
        SessionMessage(
            id = id,
            sessionId = "session",
            type = "user",
            data = "{}",
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
            roundMark = roundMark,
        )

    private fun assistant(id: String, timeCreated: Long, completedAt: Long?, roundMark: Boolean) =
        SessionMessage(
            id = id,
            sessionId = "session",
            type = "assistant",
            data = "{}",
            timeCreated = timeCreated,
            timeUpdated = completedAt ?: timeCreated,
            completedAt = completedAt,
            roundMark = roundMark,
        )
}
