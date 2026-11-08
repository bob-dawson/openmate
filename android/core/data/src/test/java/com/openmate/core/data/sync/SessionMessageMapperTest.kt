package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.openmate.core.network.dto.SyncMessageDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SessionMessageMapperTest {

    private fun dto(
        type: String,
        data: String,
        timeUpdated: Long = 2000L,
    ) = SyncMessageDto(
        id = "msg-1",
        sessionId = "ses-1",
        type = type,
        timeCreated = 1000L,
        timeUpdated = timeUpdated,
        data = Json.parseToJsonElement(data).jsonObject,
    )

    @Test
    fun compactionCompleted_marksCompletedAtFromStatus() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "compaction",
                data = """{"time":{"created":1000},"status":"completed","reason":"manual","summary":"s","recent":""}""",
                timeUpdated = 2000L,
            ),
        )

        assertThat(entity.completedAt).isEqualTo(2000L)
    }

    @Test
    fun compactionFailed_marksCompletedAtSoItStopsShowingRunning() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "compaction",
                data = """{"time":{"created":1000},"status":"failed","reason":"auto","error":{"type":"unknown"}}""",
                timeUpdated = 2500L,
            ),
        )

        assertThat(entity.completedAt).isEqualTo(2500L)
    }

    @Test
    fun compactionRunning_hasNoCompletedAt() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "compaction",
                data = """{"time":{"created":1000},"status":"running","reason":"auto","summary":"","recent":""}""",
            ),
        )

        assertThat(entity.completedAt).isNull()
    }

    @Test
    fun isCompactionSettled_matchesSettledStatuses() {
        fun parse(status: String) = Json.parseToJsonElement("""{"status":"$status"}""").jsonObject

        assertThat(SessionMessageMapper.isCompactionSettled(parse("completed"))).isTrue()
        assertThat(SessionMessageMapper.isCompactionSettled(parse("failed"))).isTrue()
        assertThat(SessionMessageMapper.isCompactionSettled(parse("running"))).isFalse()
        assertThat(SessionMessageMapper.isCompactionSettled(Json.parseToJsonElement("{}").jsonObject)).isFalse()
    }

    @Test
    fun compactionWithExplicitCompletedTime_prefersThatValue() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "compaction",
                data = """{"time":{"created":1000,"completed":1500},"status":"completed"}""",
                timeUpdated = 2000L,
            ),
        )

        assertThat(entity.completedAt).isEqualTo(1500L)
    }

    @Test
    fun assistantStillUsesTimeCompleted() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "assistant",
                data = """{"time":{"created":1000,"completed":1800},"finish":"stop"}""",
                timeUpdated = 2000L,
            ),
        )

        assertThat(entity.completedAt).isEqualTo(1800L)
    }

    @Test
    fun assistantWithoutCompletedTime_hasNoCompletedAt() {
        val entity = SessionMessageMapper.dtoToEntity(
            dto(
                type = "assistant",
                data = """{"time":{"created":1000},"agent":"build"}""",
            ),
        )

        assertThat(entity.completedAt).isNull()
    }
}
