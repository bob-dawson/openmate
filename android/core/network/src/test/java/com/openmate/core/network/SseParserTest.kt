package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SseParserTest {
    @Test
    fun parseLine_validDataLine() {
        val line = """data:{"type":"session.updated","properties":{"sessionID":"ses_123"}}"""
        val result = SseParser.parseLine(line)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("session.updated")
    }

    @Test
    fun parseLine_nonDataLine_returnsNull() {
        assertThat(SseParser.parseLine("event:message")).isNull()
        assertThat(SseParser.parseLine("id:123")).isNull()
        assertThat(SseParser.parseLine("")).isNull()
        assertThat(SseParser.parseLine("some random text")).isNull()
    }

    @Test
    fun parseLine_emptyData_returnsNull() {
        assertThat(SseParser.parseLine("data:")).isNull()
        assertThat(SseParser.parseLine("data: ")).isNull()
    }

    @Test
    fun parseChunk_multipleLines() {
        val chunk = """
            data:{"type":"session.created","properties":{}}
            data:{"type":"message.updated","properties":{}}
            event:ignore
        """.trimIndent()
        val results = SseParser.parseChunk(chunk)
        assertThat(results).hasSize(2)
        assertThat(results[0].type).isEqualTo("session.created")
        assertThat(results[1].type).isEqualTo("message.updated")
    }

    @Test
    fun parseLine_serverHeartbeat() {
        val line = """data:{"type":"server.heartbeat","properties":{}}"""
        val result = SseParser.parseLine(line)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("server.heartbeat")
    }
}
