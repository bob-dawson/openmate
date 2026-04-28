package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun parseLine_globalSyncEvent_unwrapsSyncEvent() {
        val line = """data:{"directory":"/proj","project":"p","workspace":"w","payload":{"type":"sync","syncEvent":{"type":"message.updated.1","id":"evt_1","seq":1,"aggregateID":"ses_1","data":{"sessionID":"ses_1","info":{"id":"msg_1","role":"user"}}}}}"""
        val result = SseParser.parseLine(line)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("message.updated")
        assertThat(result.properties["sessionID"]!!.jsonPrimitive.content).isEqualTo("ses_1")
    }

    @Test
    fun parseLine_globalBusEvent_unwrapsPayload() {
        val line = """data:{"directory":"/proj","project":"p","workspace":"w","payload":{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}}"""
        val result = SseParser.parseLine(line)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("session.status")
        assertThat(result.properties["sessionID"]!!.jsonPrimitive.content).isEqualTo("ses_1")
    }
}
