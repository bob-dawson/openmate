package com.openmate.core.domain.model

enum class DiffLineType { CONTEXT, ADD, REMOVE }

data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

data class DiffFile(
    val filePath: String,
    val hunks: List<DiffHunk>,
)
