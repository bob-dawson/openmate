package com.openmate.syncdebugger

import kotlinx.serialization.Serializable

@Serializable
data class StepResult(
    val seq: Long,
    val eventType: String,
    val eventId: String,
    val cacheState: CacheSnapshot? = null,
    val changeType: String? = null,
    val changeDetail: String? = null,
    val skipReason: String? = null,
    val batch: Int = 1,
)

@Serializable
data class CacheSnapshot(
    val id: String? = null,
    val type: String? = null,
    val toolCount: Int = 0,
)

@Serializable
data class SyncResult(
    val sessionId: String,
    val afterSeq: Long,
    val totalEvents: Int,
    val batches: Int = 1,
    val steps: List<StepResult>,
    val summary: Summary,
)

@Serializable
data class Summary(
    val totalChanges: Int,
    val skippedEvents: Int,
    val skipReasons: Map<String, Int>,
    val perf: PerfSummary? = null,
)

@Serializable
data class PerfSummary(
    val totalWallMs: Long = 0,
    val fetchMs: Long = 0,
    val replayMs: Long = 0,
    val dbWriteMs: Long = 0,
    val loaderCalls: Map<String, Int> = emptyMap(),
    val loaderCacheHits: Int = 0,
    val loaderCacheMisses: Int = 0,
    val replayerCreatedCount: Int = 1,
    val batchTimings: List<BatchTiming> = emptyList(),
)

@Serializable
data class BatchTiming(
    val batch: Int,
    val fetchMs: Long,
    val replayMs: Long,
    val dbWriteMs: Long,
    val events: Int,
)

class OutputFormatter {
    fun formatStepConsole(step: StepResult): String {
        val cacheStr = step.cacheState?.let { c ->
            val id = c.id?.takeLast(8) ?: "null"
            "${c.type ?: "?"}..${id}(tools=${c.toolCount})"
        } ?: "null"
        val changeStr = step.changeType?.let { "$it ${step.changeDetail?.takeLast(8) ?: ""}" } ?: "SKIP"
        val skipStr = step.skipReason?.let { " ($it)" } ?: ""
        val batchStr = if (step.batch > 1) "[B${step.batch}]" else ""
        return "[seq=${step.seq}]$batchStr ${step.eventType} | cache=$cacheStr | $changeStr$skipStr"
    }

    fun formatSummaryConsole(result: SyncResult): String {
        val perf = result.summary.perf
        val perfLines = if (perf != null) {
            """
            |--- Performance ---
            |Total wall: ${perf.totalWallMs}ms
            |  Fetch: ${perf.fetchMs}ms (${perf.fetchMs * 100 / maxOf(perf.totalWallMs, 1)}%)
            |  Replay: ${perf.replayMs}ms (${perf.replayMs * 100 / maxOf(perf.totalWallMs, 1)}%)
            |  DB write: ${perf.dbWriteMs}ms (${perf.dbWriteMs * 100 / maxOf(perf.totalWallMs, 1)}%)

            |  Replayer created: ${perf.replayerCreatedCount}x
            |  Loader calls: ${perf.loaderCalls}
            |  Top 5 slowest batches:
            ${perf.batchTimings.sortedByDescending { it.replayMs }.take(5).joinToString("\n") { b ->
                "|    B${b.batch}: ${b.events}evts fetch=${b.fetchMs}ms replay=${b.replayMs}ms db=${b.dbWriteMs}ms"
            }}
            """.trimMargin()
        } else ""
        return """
            |=== Summary ===
            |Session: ${result.sessionId}
            |Events: ${result.totalEvents} (batches=${result.batches})
            |Changes: ${result.summary.totalChanges}
            |Skipped: ${result.summary.skippedEvents}
            |Skip reasons: ${result.summary.skipReasons}
            $perfLines
        """.trimMargin()
    }
}
