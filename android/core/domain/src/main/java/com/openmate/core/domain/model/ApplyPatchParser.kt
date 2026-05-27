package com.openmate.core.domain.model

object ApplyPatchParser {

    fun parse(patchText: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        val lines = patchText.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val filePath = when {
                line.startsWith("*** Update File: ") -> line.removePrefix("*** Update File: ").trim()
                line.startsWith("*** Add File: ") -> line.removePrefix("*** Add File: ").trim()
                line.startsWith("*** Delete File: ") -> line.removePrefix("*** Delete File: ").trim()
                else -> {
                    i++
                    continue
                }
            }
            val isDelete = line.startsWith("*** Delete File:")
            i++

            val hunks = mutableListOf<DiffHunk>()
            while (i < lines.size) {
                if (lines[i].startsWith("*** ")) break
                val hunkHeader = Regex("""^@@(?: -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))?)? @@$""").matchEntire(lines[i])
                if (hunkHeader != null) {
                    i++
                    val oldStart = hunkHeader.groupValues[1].toIntOrNull() ?: 1
                    val oldCount = hunkHeader.groupValues[2].toIntOrNull() ?: 1
                    val newStart = hunkHeader.groupValues[3].toIntOrNull() ?: 1
                    val newCount = hunkHeader.groupValues[4].toIntOrNull() ?: 1
                    val hunkLines = mutableListOf<DiffLine>()
                    var oLine = oldStart
                    var nLine = newStart

                    while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("*** ")) {
                        val hLine = lines[i]
                        when {
                            hLine.startsWith("+") -> {
                                hunkLines.add(DiffLine(DiffLineType.ADD, hLine.removePrefix("+"), null, nLine++))
                            }
                            hLine.startsWith("-") -> {
                                hunkLines.add(DiffLine(DiffLineType.REMOVE, hLine.removePrefix("-"), oLine++, null))
                            }
                            hLine.startsWith(" ") -> {
                                hunkLines.add(DiffLine(DiffLineType.CONTEXT, hLine.removePrefix(" "), oLine++, nLine++))
                            }
                            else -> break
                        }
                        i++
                    }
                    hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, hunkLines))
                } else {
                    i++
                }
            }

            if (isDelete && hunks.isEmpty()) {
                hunks.add(DiffHunk(1, 0, 1, 0, emptyList()))
            }
            files.add(DiffFile(filePath, hunks))
        }
        return files
    }
}
