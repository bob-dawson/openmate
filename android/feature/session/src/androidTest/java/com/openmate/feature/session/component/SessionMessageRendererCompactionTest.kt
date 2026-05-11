package com.openmate.feature.session.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openmate.core.domain.model.SessionMessage
import android.os.SystemClock
import org.junit.Rule
import org.junit.Test

class SessionMessageRendererCompactionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topLevelCompactionMessage_isDisplayed() {
        val entity = SessionMessage(
            id = "compaction-1",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "auto",
                  "summary": "",
                  "time": {
                    "created": 1000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 1_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                SessionMessageRenderer(
                    entity = entity,
                    onFullContentRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("▸ compaction").assertIsDisplayed()
    }

    @Test
    fun runningCompactionMessage_displaysElapsedDuration() {
        val entity = SessionMessage(
            id = "compaction-running",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "auto",
                  "summary": "",
                  "time": {
                    "created": 1000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 1_000L,
            completedAt = null,
        )

        composeRule.setContent {
            MaterialTheme {
                SessionMessageRenderer(
                    entity = entity,
                    onFullContentRequest = {},
                    runningAnchors = mapOf(entity.id to (SystemClock.elapsedRealtime() - 125_000L)),
                )
            }
        }

        composeRule.onNodeWithText("2分", substring = true).assertIsDisplayed()
    }

    @Test
    fun completedCompactionMessage_expandsToShowSummary() {
        val entity = SessionMessage(
            id = "compaction-done",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "auto",
                  "summary": "condensed prior context",
                  "time": {
                    "created": 1000,
                    "completed": 61000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 61_000L,
            completedAt = 61_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                SessionMessageRenderer(
                    entity = entity,
                    onFullContentRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("▸ compaction").performClick()
        composeRule.onNodeWithText("▾ compaction").assertIsDisplayed()
    }

    @Test
    fun completedCompactionMessage_insideLazyColumn_expandsToShowSummary() {
        val entity = SessionMessage(
            id = "compaction-in-list",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "auto",
                  "summary": "summary inside lazy column",
                  "time": {
                    "created": 1000,
                    "completed": 61000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 61_000L,
            completedAt = 61_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    items(listOf(entity), key = { it.id }) { item ->
                        SessionMessageRenderer(
                            entity = item,
                            onFullContentRequest = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("▸ compaction").performClick()
        composeRule.onNodeWithText("▾ compaction").assertIsDisplayed()
    }

    @Test
    fun completedCompactionMessage_withMarkdownSummary_expandsWithoutCrash() {
        val entity = SessionMessage(
            id = "compaction-markdown",
            sessionId = "session-1",
            type = "compaction",
            data = """
                {
                  "reason": "auto",
                  "summary": "# Compact Summary\n\n- item one\n- item two",
                  "time": {
                    "created": 1000,
                    "completed": 61000
                  }
                }
            """.trimIndent(),
            timeCreated = 1_000L,
            timeUpdated = 61_000L,
            completedAt = 61_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                SessionMessageRenderer(
                    entity = entity,
                    onFullContentRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("▸ compaction").performClick()
        composeRule.onNodeWithText("▾ compaction").assertIsDisplayed()
    }

}
