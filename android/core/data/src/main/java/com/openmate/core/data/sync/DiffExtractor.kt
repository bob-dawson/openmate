package com.openmate.core.data.sync

import com.openmate.core.domain.model.DiffBuilder
import com.openmate.core.domain.model.DiffFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts the diff of a tool call from a raw assistant message.
 *
 * Supports both structures:
 * - V2: per-file unified diffs in `state.metadata.files[].patch`, `edit` input uses `path`
 * - V1: `state.structured.diff` / `structured.filediff.patch`, `apply_patch`, `input.filePath`
 */
internal fun extractDiffFiles(
    data: JsonObject,
    toolName: String,
    targetFilePath: String?,
): List<DiffFile> {
    val contentArray = data["content"]?.jsonArray ?: return emptyList()

    for (item in contentArray) {
        val part = item.jsonObject
        if (part["type"]?.jsonPrimitive?.contentOrNull != "tool") continue
        val state = part["state"]?.jsonObject ?: continue
        val tool = state["tool"]?.jsonPrimitive?.contentOrNull
            ?: part["name"]?.jsonPrimitive?.contentOrNull
            ?: continue
        if (tool != toolName) continue

        val meta = state["metadata"]?.jsonObject ?: state["structured"]?.jsonObject

        // V1 + V2: per-file unified diff entries.
        meta?.get("files")?.jsonArray?.let { files ->
            val parsed = files.flatMap { file ->
                val patch = file.jsonObject["patch"]?.jsonPrimitive?.contentOrNull
                if (patch.isNullOrBlank()) emptyList() else DiffBuilder.fromUnifiedDiff(patch)
            }
            val filtered = filterByPath(parsed, targetFilePath)
            if (filtered.isNotEmpty()) return filtered
        }

        // V1: single diff text.
        val diffText = meta?.get("diff")?.jsonPrimitive?.contentOrNull
            ?: meta?.get("filediff")?.jsonObject?.get("patch")?.jsonPrimitive?.contentOrNull
        if (!diffText.isNullOrBlank()) {
            val filtered = filterByPath(DiffBuilder.fromUnifiedDiff(diffText), targetFilePath)
            if (filtered.isNotEmpty()) return filtered
        }

        val input = state["input"]?.jsonObject

        // apply_patch / patch: fall back to the raw patch text.
        if (tool == "patch" || tool == "apply_patch") {
            val patchText = input?.get("patchText")?.jsonPrimitive?.contentOrNull
                ?: input?.get("patch_text")?.jsonPrimitive?.contentOrNull
            if (!patchText.isNullOrBlank()) {
                val filtered = filterByPath(DiffBuilder.fromApplyPatchFallback(patchText), targetFilePath)
                if (filtered.isNotEmpty()) return filtered
            }
        }

        // edit: rebuild the diff from the old/new strings (V2 uses `path`).
        if (tool == "edit") {
            val filePath = input.str("path")
                ?: input.str("filePath")
                ?: input.str("file_path")
                ?: return emptyList()
            val oldString = input.str("oldString") ?: input.str("old_string") ?: ""
            val newString = input.str("newString") ?: input.str("new_string") ?: ""
            val diffFile = DiffBuilder.fromEditFallback(filePath, oldString, newString) ?: return emptyList()
            return filterByPath(listOf(diffFile), targetFilePath)
        }

        return emptyList()
    }
    return emptyList()
}

/** Filters diffs to [targetFilePath] (path or basename match), or returns all when no target is given. */
private fun filterByPath(files: List<DiffFile>, targetFilePath: String?): List<DiffFile> {
    if (targetFilePath == null) return files
    val target = targetFilePath.replace('\\', '/')
    val targetBase = target.substringAfterLast('/')
    return files.filter {
        val path = it.filePath.replace('\\', '/')
        path == target || path.endsWith("/$target") || path.substringAfterLast('/') == targetBase
    }
}

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
