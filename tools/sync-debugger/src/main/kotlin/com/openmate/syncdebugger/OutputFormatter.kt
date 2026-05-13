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
        return """
            |=== Summary ===
            |Session: ${result.sessionId}
            |Events: ${result.totalEvents} (batches=${result.batches})
            |Changes: ${result.summary.totalChanges}
            |Skipped: ${result.summary.skippedEvents}
            |Skip reasons: ${result.summary.skipReasons}
        """.trimMargin()
    }
}
