package com.openmate.core.network.dto

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.Part
import kotlinx.serialization.json.Json
import org.junit.Test

class RealDataParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(name: String): String {
        val url = javaClass.classLoader!!.getResource("samples/$name")
            ?: throw IllegalArgumentException("Resource not found: samples/$name")
        return url.readText()
    }

    @Test
    fun parseSessionList_realData() {
        val data = loadResource("session_list.json")
        val startTime = System.currentTimeMillis()
        val sessions = json.decodeFromString<List<SessionDto>>(data)
        val elapsed = System.currentTimeMillis() - startTime

        assertThat(sessions).isNotEmpty()
        println("Parsed ${sessions.size} sessions in ${elapsed}ms")

        val first = sessions[0]
        assertThat(first.id).startsWith("ses_")
        assertThat(first.title).isNotEmpty()
        assertThat(first.directory).isNotEmpty()
        assertThat(first.time.created).isGreaterThan(0L)
        assertThat(first.time.updated).isGreaterThan(0L)

        val withPermission = sessions.first { it.permission != null }
        println("Session with permission: ${withPermission.id}")
        assertThat(withPermission.permission).isNotNull()

        val withParent = sessions.firstOrNull { it.parentID != null }
        if (withParent != null) {
            assertThat(withParent.parentID).startsWith("ses_")
            println("Session with parent: ${withParent.id} -> ${withParent.parentID}")
        }

        val withRevert = sessions.firstOrNull { it.revert != null }
        if (withRevert != null) {
            assertThat(withRevert.revert!!.messageID).isNotEmpty()
            println("Session with revert: ${withRevert.id}")
        }

        sessions.forEach { s ->
            assertThat(s.id).isNotEmpty()
            assertThat(s.slug).isNotEmpty()
            assertThat(s.title).isNotEmpty()
            assertThat(s.directory).isNotEmpty()
            assertThat(s.projectID).isNotEmpty()
            assertThat(s.version).isNotEmpty()
            assertThat(s.time.created).isGreaterThan(0L)
            assertThat(s.time.updated).isGreaterThan(0L)
        }
        println("All ${sessions.size} sessions validated OK")
    }

    @Test
    fun parseSessionDetail_realData() {
        val data = loadResource("session_detail.json")
        val session = json.decodeFromString<SessionDto>(data)

        assertThat(session.id).isEqualTo("ses_22ec71f86ffempzVEd7iSlnTzw")
        assertThat(session.slug).isEqualTo("eager-lagoon")
        assertThat(session.projectID).isEqualTo("global")
        assertThat(session.title).isEqualTo("Greeting check-in conversation")
        assertThat(session.version).isEqualTo("1.14.28")
        assertThat(session.summary).isNotNull()
        assertThat(session.summary!!.additions).isEqualTo(0)
        assertThat(session.summary.files).isEqualTo(0)
        assertThat(session.time.created).isEqualTo(1777331658873L)
        assertThat(session.time.updated).isEqualTo(1777331662404L)
    }

    @Test
    fun parseSessionList_toDomain_allFields() {
        val data = loadResource("session_list.json")
        val sessions = json.decodeFromString<List<SessionDto>>(data)

        val domains = sessions.map { it.toDomain() }
        assertThat(domains).hasSize(sessions.size)

        domains.forEach { d ->
            assertThat(d.id).isNotEmpty()
            assertThat(d.title).isNotEmpty()
            assertThat(d.directory).isNotEmpty()
            assertThat(d.createdAt).isGreaterThan(0L)
            assertThat(d.updatedAt).isGreaterThan(0L)
        }

        val withPermission = domains[sessions.indexOfFirst { it.permission != null }]
        assertThat(withPermission.id).isNotEmpty()
    }

    @Test
    fun parseMessages_realData_simple() {
        val data = loadResource("messages.json")
        val startTime = System.currentTimeMillis()
        val messages = json.decodeFromString<List<MessageWithPartsDto>>(data)
        val elapsed = System.currentTimeMillis() - startTime

        assertThat(messages).hasSize(2)
        println("Parsed ${messages.size} messages in ${elapsed}ms")

        val userMsg = messages[0]
        assertThat(userMsg.info.role).isEqualTo("user")
        assertThat(userMsg.info.id).startsWith("msg_")
        assertThat(userMsg.info.time.created).isGreaterThan(0L)
        assertThat(userMsg.parts).hasSize(1)
        assertThat(userMsg.parts[0].type).isEqualTo("text")
        assertThat(userMsg.parts[0].text).isEqualTo("hi")

        val assistantMsg = messages[1]
        assertThat(assistantMsg.info.role).isEqualTo("assistant")
        assertThat(assistantMsg.info.parentID).isNotNull()
        assertThat(assistantMsg.info.cost).isNotNull()
        assertThat(assistantMsg.info.tokens).isNotNull()
        assertThat(assistantMsg.info.time.completed).isNotNull()
    }

    @Test
    fun parseMessages_realData_withToolInvocation() {
        val data = loadResource("messages_with_tool_invocation.json")
        val messages = json.decodeFromString<List<MessageWithPartsDto>>(data)
        assertThat(messages).isNotEmpty()

        val msgWithTool = messages.first { msg -> msg.parts.any { it.type == "tool" } }
        val toolPart = msgWithTool.parts.first { it.type == "tool" }
        assertThat(toolPart.tool).isNotEmpty()
        assertThat(toolPart.callID).isNotEmpty()
        assertThat(toolPart.state).isNotNull()
        assertThat(toolPart.state!!.status).isAnyOf("pending", "running", "completed", "error")
        println("Tool: ${toolPart.tool}, status: ${toolPart.state!!.status}")

        val reasoningParts = messages.flatMap { it.parts }.filter { it.type == "reasoning" }
        if (reasoningParts.isNotEmpty()) {
            println("Found ${reasoningParts.size} reasoning parts")
        }

        val stepStarts = messages.flatMap { it.parts }.filter { it.type == "step-start" }
        assertThat(stepStarts).isNotEmpty()
        println("Found ${stepStarts.size} step-start parts")
    }

    @Test
    fun parseMessages_realData_currentSession() {
        val data = loadResource("messages_current_session.json")
        val startTime = System.currentTimeMillis()
        val messages = json.decodeFromString<List<MessageWithPartsDto>>(data)
        val elapsed = System.currentTimeMillis() - startTime

        assertThat(messages).isNotEmpty()
        println("Parsed ${messages.size} messages (current session) in ${elapsed}ms")

        val allParts = messages.flatMap { it.parts }
        val partTypes = allParts.map { it.type }.distinct()
        println("Part types found: $partTypes")

        allParts.forEach { part ->
            assertThat(part.type).isNotEmpty()
        }

        val toolParts = allParts.filter { it.type == "tool" }
        if (toolParts.isNotEmpty()) {
            val tp = toolParts[0]
            assertThat(tp.tool).isNotEmpty()
            assertThat(tp.callID).isNotEmpty()
            assertThat(tp.state).isNotNull()
            println("Sample tool: ${tp.tool}, callID: ${tp.callID}, status: ${tp.state!!.status}")
        }
    }

    @Test
    fun parseMessages_toDomain_allMessages() {
        val data = loadResource("messages_current_session.json")
        val messages = json.decodeFromString<List<MessageWithPartsDto>>(data)
        val domains = messages.map { it.toDomain() }

        assertThat(domains).hasSize(messages.size)
        domains.forEach { msg ->
            assertThat(msg.id).isNotEmpty()
            assertThat(msg.createdAt).isGreaterThan(0L)
            msg.parts.forEach { part ->
                when (part) {
                    is Part.TextPart -> assertThat(part.text).isNotNull()
                    is Part.ToolInvocationPart -> {
                        assertThat(part.toolName).isNotEmpty()
                        assertThat(part.toolCallID).isNotEmpty()
                    }
                    is Part.ReasoningPart -> assertThat(part.text).isNotNull()
                    else -> {}
                }
            }
        }
        println("All ${domains.size} messages converted to domain OK")
    }

    @Test
    fun performance_largeSessionList() {
        val data = loadResource("session_list.json")
        val iterations = 100
        val startTime = System.currentTimeMillis()
        repeat(iterations) {
            json.decodeFromString<List<SessionDto>>(data)
        }
        val elapsed = System.currentTimeMillis() - startTime
        val avgMs = elapsed.toDouble() / iterations
        println("Session list parsing: $iterations iterations, total ${elapsed}ms, avg ${String.format("%.2f", avgMs)}ms")
        assertThat(avgMs).isLessThan(50.0)
    }
}
