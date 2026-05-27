package com.openmate.core.domain.model

object DiffBuilder {

    fun fromUnifiedDiff(diffText: String): List<DiffFile> {
        return UnifiedDiffParser.parse(diffText)
    }

    fun fromEditFallback(filePath: String, oldString: String, newString: String): DiffFile? {
        val oldLines = if (oldString.isEmpty()) emptyList() else oldString.lines()
        val newLines = if (newString.isEmpty()) emptyList() else newString.lines()
        val diffLines = MyersDiff.diff(oldLines, newLines)
        val hunks = MyersDiff.groupIntoHunks(diffLines)
        return DiffFile(filePath, hunks)
    }

    fun fromApplyPatchFallback(patchText: String): List<DiffFile> {
        return ApplyPatchParser.parse(patchText)
    }
}