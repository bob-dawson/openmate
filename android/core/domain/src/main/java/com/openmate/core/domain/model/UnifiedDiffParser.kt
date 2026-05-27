package com.openmate.core.domain.model

object UnifiedDiffParser {

    private val hunkHeaderRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    fun parse(diffText: String): List<DiffFile> {
        val lines = diffText.lines()
        val files = mutableListOf<DiffFile>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (!isFileHeader(line)) {
                i++
                continue
            }

            var filePath = ""
            if (line.startsWith("Index: ")) {
                filePath = line.removePrefix("Index: ").trim()
                i++
                if (i < lines.size && lines[i].startsWith("===")) i++
            } else if (line.startsWith("diff --git ")) {
                val parts = line.removePrefix("diff --git ").split(" ")
                filePath = parts.lastOrNull()?.removePrefix("b/") ?: ""
                i++
            }

            if (i < lines.size && lines[i].startsWith("--- ")) {
                if (filePath.isBlank()) {
                    filePath = lines[i].removePrefix("--- ").trim().removePrefix("a/")
                }
                i++
            }
            if (i < lines.size && lines[i].startsWith("+++ ")) {
                if (filePath.isBlank()) {
                    filePath = lines[i].removePrefix("+++ ").trim().removePrefix("b/")
                }
                i++
            }

            val hunks = mutableListOf<DiffHunk>()
            while (i < lines.size) {
                val hunkMatch = hunkHeaderRegex.matchEntire(lines[i])
                if (hunkMatch != null) {
                    val oldStart = hunkMatch.groupValues[1].toInt()
                    val oldCount = hunkMatch.groupValues[2].toIntOrNull() ?: 1
                    val newStart = hunkMatch.groupValues[3].toInt()
                    val newCount = hunkMatch.groupValues[4].toIntOrNull() ?: 1
                    i++
                    val hunkLines = mutableListOf<DiffLine>()
                    var oLine = oldStart
                    var nLine = newStart

                    while (i < lines.size) {
                        val hLine = lines[i]
                        when {
                            hLine.startsWith("+") -> {
                                hunkLines.add(DiffLine(DiffLineType.ADD, hLine.removePrefix("+"), null, nLine++))
                                i++
                            }
                            hLine.startsWith("-") -> {
                                hunkLines.add(DiffLine(DiffLineType.REMOVE, hLine.removePrefix("-"), oLine++, null))
                                i++
                            }
                            hLine.startsWith(" ") -> {
                                hunkLines.add(DiffLine(DiffLineType.CONTEXT, hLine.removePrefix(" "), oLine++, nLine++))
                                i++
                            }
                            isFileHeader(hLine) || hunkHeaderRegex.matches(hLine) -> break
                            else -> {
                                hunkLines.add(DiffLine(DiffLineType.CONTEXT, hLine, oLine++, nLine++))
                                i++
                            }
                        }
                    }
                    hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, hunkLines))
                } else if (isFileHeader(lines[i])) {
                    break
                } else {
                    i++
                }
            }

            files.add(DiffFile(filePath.ifBlank { "unknown" }, hunks))
        }
        return files
    }

    private fun isFileHeader(line: String): Boolean {
        return line.startsWith("Index: ")
            || line.startsWith("diff --git ")
            || line.startsWith("--- ")
    }
}