package com.openmate.core.domain.model

object MyersDiff {

    fun diff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        val n = oldLines.size
        val m = newLines.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return newLines.mapIndexed { i, line ->
            DiffLine(DiffLineType.ADD, line, null, i + 1)
        }
        if (m == 0) return oldLines.mapIndexed { i, line ->
            DiffLine(DiffLineType.REMOVE, line, i + 1, null)
        }

        val trace = shortestEdit(oldLines, newLines)
        val edits = backtrack(trace, n, m)
        return buildDiffLines(oldLines, newLines, edits)
    }

    private fun shortestEdit(a: List<String>, b: List<String>): List<IntArray> {
        val n = a.size
        val m = b.size
        val max = n + m
        val size = 2 * max + 1
        val v = IntArray(size) { 0 }
        v[max + 1] = 0
        val trace = mutableListOf<IntArray>()

        for (d in 0..max) {
            trace.add(v.copyOf())
            var done = false
            for (k in -d..d step 2) {
                val idx = k + max
                var x: Int
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1]
                } else {
                    x = v[idx - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[idx] = x
                if (x >= n && y >= m) {
                    done = true
                }
            }
            if (done) break
        }
        return trace
    }

    private fun backtrack(trace: List<IntArray>, n: Int, m: Int): List<Edit> {
        val max = n + m
        val size = 2 * max + 1
        val edits = mutableListOf<Edit>()
        var x = n
        var y = m

        for (d in trace.lastIndex downTo 1) {
            val v = trace[d]
            val prevV = trace[d - 1]
            val k = x - y
            val idx = k + max

            val prevK: Int
            if (k == -d || (k != d && prevV[idx - 1] < prevV[idx + 1])) {
                prevK = k + 1
            } else {
                prevK = k - 1
            }

            val prevX = prevV[prevK + max]
            val prevY = prevX - prevK

            while (x > prevX && y > prevY) {
                edits.add(Edit.CONTEXT)
                x--
                y--
            }

            if (d > 0) {
                if (x == prevX) {
                    edits.add(Edit.INSERT)
                    y--
                } else {
                    edits.add(Edit.DELETE)
                    x--
                }
            }
        }

        edits.reverse()
        return edits
    }

    private fun buildDiffLines(a: List<String>, b: List<String>, edits: List<Edit>): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var ai = 0
        var bi = 0

        for (edit in edits) {
            when (edit) {
                Edit.CONTEXT -> {
                    result.add(DiffLine(DiffLineType.CONTEXT, a[ai], ai + 1, bi + 1))
                    ai++
                    bi++
                }
                Edit.DELETE -> {
                    result.add(DiffLine(DiffLineType.REMOVE, a[ai], ai + 1, null))
                    ai++
                }
                Edit.INSERT -> {
                    result.add(DiffLine(DiffLineType.ADD, b[bi], null, bi + 1))
                    bi++
                }
            }
        }
        return result
    }

    fun groupIntoHunks(lines: List<DiffLine>, contextLines: Int = 3): List<DiffHunk> {
        if (lines.isEmpty()) return emptyList()

        val changes = lines.mapIndexedNotNull { idx, line ->
            if (line.type != DiffLineType.CONTEXT) idx else null
        }
        if (changes.isEmpty()) return emptyList()

        val groups = mutableListOf<IntRange>()
        var groupStart = changes.first()
        var groupEnd = changes.first()

        for (i in 1 until changes.size) {
            if (changes[i] <= groupEnd + 2 * contextLines + 1) {
                groupEnd = changes[i]
            } else {
                groups.add(groupStart..groupEnd)
                groupStart = changes[i]
                groupEnd = changes[i]
            }
        }
        groups.add(groupStart..groupEnd)

        return groups.map { range ->
            val start = maxOf(0, range.first - contextLines)
            val end = minOf(lines.lastIndex, range.last + contextLines)
            val hunkLines = lines.subList(start, end + 1)

            val oldStart = hunkLines.firstNotNullOfOrNull { it.oldLineNumber } ?: 1
            val newStart = hunkLines.firstNotNullOfOrNull { it.newLineNumber } ?: 1
            val oldCount = hunkLines.count { it.type == DiffLineType.CONTEXT || it.type == DiffLineType.REMOVE }
            val newCount = hunkLines.count { it.type == DiffLineType.CONTEXT || it.type == DiffLineType.ADD }

            DiffHunk(oldStart, oldCount, newStart, newCount, hunkLines)
        }
    }

    private enum class Edit { CONTEXT, DELETE, INSERT }
}
