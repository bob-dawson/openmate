package com.openmate.feature.session.component

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SessionMessageRendererTest {

    @Test
    fun extractAssistantErrorMessage_returnsNestedErrorMessage() {
        val data = Json.parseToJsonElement(
            """
            {
              "finish": "error",
              "error": {
                "message": "Provider overloaded, retry later"
              },
              "content": [
                {
                  "type": "text",
                  "text": ""
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        assertThat(extractAssistantErrorMessage(data)).isEqualTo("Provider overloaded, retry later")
    }

    @Test
    fun extractSubtaskSessionId_acceptsUppercaseSessionIdInMetadata() {
        val metadata = buildJsonObject {
            put("sessionID", JsonPrimitive("ses_subtask_upper"))
        }

        assertThat(extractSubtaskSessionId(metadata = metadata, structured = null, resultText = null))
            .isEqualTo("ses_subtask_upper")
    }

    @Test
    fun extractSubtaskSessionId_fallsBackToStructuredSessionIdWhileRunning() {
        val structured = buildJsonObject {
            put("sessionId", JsonPrimitive("ses_subtask_structured"))
        }

        assertThat(extractSubtaskSessionId(metadata = null, structured = structured, resultText = null))
            .isEqualTo("ses_subtask_structured")
    }

    @Test
    fun extractSubtaskSessionId_acceptsUppercaseSessionIdInStructuredPayload() {
        val structured = buildJsonObject {
            put("sessionID", JsonPrimitive("ses_subtask_structured_upper"))
        }

        assertThat(extractSubtaskSessionId(metadata = null, structured = structured, resultText = null))
            .isEqualTo("ses_subtask_structured_upper")
    }

    @Test
    fun parseQuestionArgs_keepsCustomFlag() {
        val questions = parseQuestionArgs(
            """
            {
              "questions": [
                {
                  "header": "Need info",
                  "question": "Choose one",
                  "multiple": false,
                  "custom": false,
                  "options": [
                    {"label": "A", "description": ""}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertThat(questions).hasSize(1)
        assertThat(questions!!.single().custom).isFalse()
    }

    @Test
    fun extractQuestionAnswers_returnsStructuredAnswers() {
        val metadata = buildJsonObject {
            put(
                "answers",
                JsonArray(
                    listOf(
                        JsonArray(listOf(JsonPrimitive("Usage"), JsonPrimitive("custom-note"))),
                        JsonArray(emptyList()),
                    ),
                ),
            )
        }

        assertThat(extractQuestionAnswers(metadata))
            .isEqualTo(listOf(listOf("Usage", "custom-note"), emptyList()))
    }

    @Test
    fun isDismissedQuestionError_matchesDismissedMessage() {
        assertThat(isDismissedQuestionError("Error: user dismissed this question")).isTrue()
    }

    @Test
    fun isDismissedQuestionError_ignoresOtherErrors() {
        assertThat(isDismissedQuestionError("Error: provider overloaded")).isFalse()
    }

    @Test
    fun buildQuestionAnswerRows_pairsQuestionsWithAnswers() {
        val rows = buildQuestionAnswerRows(
            questions = parseQuestionArgs(
                """
                {
                  "questions": [
                    {
                      "question": "What do you need?",
                      "header": "",
                      "multiple": true,
                      "custom": true,
                      "options": [
                        {"label": "Diff", "description": ""},
                        {"label": "Usage", "description": ""}
                      ]
                    },
                    {
                      "question": "Anything else?",
                      "header": "",
                      "multiple": false,
                      "custom": true,
                      "options": []
                    }
                  ]
                }
                """.trimIndent(),
            )!!,
            answers = listOf(
                listOf("Usage", "custom-note"),
                emptyList(),
            ),
        )

        assertThat(rows).hasSize(2)
        assertThat(rows[0].question).isEqualTo("What do you need?")
        assertThat(rows[0].answers).containsExactly("Usage", "custom-note").inOrder()
        assertThat(rows[1].question).isEqualTo("Anything else?")
        assertThat(rows[1].answers).isEmpty()
    }

    @Test
    fun toolSummary_usesShellLabelForBashTool() {
        val summary = toolSummary(
            toolName = "bash",
            args = """{"command":"echo hello","description":"Runs hello"}""",
            result = null,
        )

        assertThat(summary.icon).isEqualTo("shell")
        assertThat(summary.text).isEqualTo("echo hello")
    }

    @Test
    fun shouldExpandRunningTool_expandsBashWhenCommandAvailable() {
        val item = DisplayItem.ToolItem(
            toolName = "bash",
            state = com.openmate.core.domain.model.ToolCallState.RUNNING,
            args = """{"command":"npm install","description":"Install deps"}""",
            result = null,
            files = emptyList(),
            hash = null,
        )

        assertThat(shouldExpandRunningTool(item)).isTrue()
    }

    @Test
    fun shouldExpandRunningTool_keepsOtherRunningToolsCollapsed() {
        val item = DisplayItem.ToolItem(
            toolName = "grep",
            state = com.openmate.core.domain.model.ToolCallState.RUNNING,
            args = """{"pattern":"foo"}""",
            result = null,
            files = emptyList(),
            hash = null,
        )

        assertThat(shouldExpandRunningTool(item)).isFalse()
    }

    @Test
    fun extractToolItems_attachesFollowingPatchFilesToApplyPatchTool() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "apply_patch",
                  "id": "tool-1",
                  "state": {
                    "status": "completed",
                    "input": "{\"patch\":\"*** Begin Patch\\n*** Update File: foo.txt\\n*** End Patch\"}",
                    "content": [
                      {"text": "Patch applied"}
                    ]
                  }
                },
                {
                  "type": "patch",
                  "id": "patch-1",
                  "hash": "abc",
                  "files": ["foo.txt", "bar/baz.kt"]
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val toolItems = extractToolItems(data)

        assertThat(toolItems).hasSize(1)
        assertThat(toolItems.single().toolName).isEqualTo("apply_patch")
        assertThat(toolItems.single().files).containsExactly("foo.txt", "bar/baz.kt").inOrder()
    }

    @Test
    fun extractToolItems_keepsAllFilesFromRealPatchPayload() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "apply_patch",
                  "id": "tool-1",
                  "state": {
                    "status": "error",
                    "input": {
                      "patch": "*** Begin Patch"
                    },
                    "error": "Tool execution aborted"
                  }
                },
                {
                  "type": "patch",
                  "id": "patch-1",
                  "hash": "abc",
                  "files": [
                    "D:/openmate/android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt",
                    "D:/openmate/opencode-bridge/src/sync/db.rs"
                  ]
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val toolItem = extractToolItems(data).single()

        assertThat(toolItem.files).containsExactly(
            "D:/openmate/android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt",
            "D:/openmate/opencode-bridge/src/sync/db.rs",
        ).inOrder()
    }

    @Test
    fun extractToolItems_promotesStructuredSessionIdForRunningTaskTool() {
        val data = Json.parseToJsonElement(
            """
            {
              "content": [
                {
                  "type": "tool",
                  "name": "task",
                  "id": "tool-task-1",
                  "state": {
                    "status": "running",
                    "input": "{\"description\":\"subtask\"}",
                    "structured": {
                      "sessionID": "ses_running_subtask"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val toolItem = extractToolItems(data).single()

        assertThat(toolItem.toolName).isEqualTo("task")
        assertThat(extractSubtaskSessionId(toolItem.metadata, null, toolItem.result))
            .isEqualTo("ses_running_subtask")
    }

    @Test
    fun extractApplyPatchResultFiles_parsesUpdatedFilesFromToolOutput() {
        val files = extractApplyPatchResultFiles(
            """
            Success. Updated the following files:
            M 待跟踪问题.md
            M android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt
            A docs/new-file.md
            D old/obsolete.txt
            Note: 3 files changed
            """.trimIndent(),
        )

        assertThat(files).containsExactly(
            "待跟踪问题.md",
            "android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt",
            "docs/new-file.md",
            "old/obsolete.txt",
        ).inOrder()
    }
}
