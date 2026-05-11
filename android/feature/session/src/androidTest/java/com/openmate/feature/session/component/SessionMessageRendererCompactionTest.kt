package com.openmate.feature.session.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.openmate.core.domain.model.SessionMessage
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
}
