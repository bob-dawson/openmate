package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class DiffExtractorTest {

    private fun message(json: String) = Json.parseToJsonElement(json).jsonObject

    private val v2Edit = message(
        """
        {
          "content": [
            {
              "type": "tool",
              "name": "edit",
              "state": {
                "status": "completed",
                "input": {"path": "TODO.md", "oldString": "a", "newString": "b"},
                "metadata": {
                  "files": [
                    {
                      "file": "TODO.md",
                      "patch": "Index: TODO.md\n===================================================================\n--- TODO.md\n+++ TODO.md\n@@ -1,2 +1,2 @@\n-a\n+b\n",
                      "status": "modified",
                      "additions": 1,
                      "deletions": 1
                    }
                  ],
                  "truncated": false
                }
              }
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun v2Edit_metadataFilesPatch_isParsed() {
        val files = extractDiffFiles(v2Edit, "edit", "TODO.md")
        assertThat(files).hasSize(1)
        assertThat(files[0].filePath).isEqualTo("TODO.md")
        assertThat(files[0].hunks).isNotEmpty()
    }

    @Test
    fun v2Edit_withoutTargetPath_returnsAll() {
        val files = extractDiffFiles(v2Edit, "edit", null)
        assertThat(files).hasSize(1)
    }

    @Test
    fun v2Edit_otherTarget_returnsEmpty() {
        val files = extractDiffFiles(v2Edit, "edit", "other.md")
        assertThat(files).isEmpty()
    }

    @Test
    fun v2Edit_fullPathTarget_matchesByBasename() {
        val files = extractDiffFiles(v2Edit, "edit", "D:\\moontown\\TODO.md")
        assertThat(files).hasSize(1)
        assertThat(files[0].filePath).isEqualTo("TODO.md")
    }

    @Test
    fun v2Edit_withoutMetadata_fallsBackToInputPath() {
        val msg = message(
            """{"content":[{"type":"tool","name":"edit","state":{"input":{"path":"src/a.kt","oldString":"old","newString":"new"}}}]}"""
        )
        val files = extractDiffFiles(msg, "edit", "src/a.kt")
        assertThat(files).hasSize(1)
        assertThat(files[0].filePath).isEqualTo("src/a.kt")
    }

    @Test
    fun v1Edit_structuredDiff_isParsed() {
        val msg = message(
            """{"content":[{"type":"tool","name":"edit","state":{"input":{"filePath":"a.txt"},"structured":{"diff":"--- a.txt\n+++ a.txt\n@@ -1 +1 @@\n-a\n+b\n"}}}]}"""
        )
        val files = extractDiffFiles(msg, "edit", "a.txt")
        assertThat(files).hasSize(1)
        assertThat(files[0].filePath).isEqualTo("a.txt")
    }

    @Test
    fun v2Patch_metadataFilesPatch_isParsed() {
        val msg = message(
            """{"content":[{"type":"tool","name":"patch","state":{"input":{},"metadata":{"files":[{"file":"x.md","patch":"Index: x.md\n===================================================================\n--- x.md\n+++ x.md\n@@ -1 +1 @@\n-a\n+b\n"}]}}}]}"""
        )
        val files = extractDiffFiles(msg, "patch", "x.md")
        assertThat(files).hasSize(1)
        assertThat(files[0].filePath).isEqualTo("x.md")
    }

    @Test
    fun unknownTool_returnsEmpty() {
        val msg = message(
            """{"content":[{"type":"tool","name":"shell","state":{"input":{"command":"ls"},"metadata":{}}}]}"""
        )
        assertThat(extractDiffFiles(msg, "shell", null)).isEmpty()
    }
}
