package com.openmate.v2explorer

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.Base64

class V2Client(
    private val baseUrl: String,
    private val password: String,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val basicAuth = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())

    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30000 }
    }

    private fun HttpRequestBuilder.applyAuth() {
        header(HttpHeaders.Authorization, basicAuth)
    }

    suspend fun health(): JsonElement {
        val resp = httpClient.get("$baseUrl/api/health") { applyAuth() }
        return json.parseToJsonElement(resp.bodyAsText())
    }

    suspend fun listSessions(): JsonObject {
        val resp = httpClient.get("$baseUrl/api/session") { applyAuth() }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun getMessages(sessionId: String, cursor: String? = null): JsonObject {
        val resp = httpClient.get("$baseUrl/api/session/$sessionId/message") {
            applyAuth()
            if (cursor != null) parameter("cursor", cursor)
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun getEvents(sessionId: String, after: Long = 0, limit: Int = 100): JsonObject {
        val resp = httpClient.get("$baseUrl/api/session/$sessionId/history") {
            applyAuth()
            parameter("after", after)
            parameter("limit", limit)
        }
        return if (resp.status == HttpStatusCode.OK) {
            json.parseToJsonElement(resp.bodyAsText()).jsonObject
        } else {
            buildJsonObject { put("error", "HTTP ${resp.status.value}") }
        }
    }

    suspend fun sendPrompt(sessionId: String, text: String): JsonObject {
        val resp = httpClient.post("$baseUrl/api/session/$sessionId/prompt") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) }.toString())
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun revertStage(sessionId: String, messageID: String): JsonObject {
        val resp = httpClient.post("$baseUrl/api/session/$sessionId/revert/stage") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("messageID", messageID) }.toString())
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun revertCommit(sessionId: String): HttpStatusCode {
        val resp = httpClient.post("$baseUrl/api/session/$sessionId/revert/commit") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return resp.status
    }

    suspend fun createSession(): JsonObject {
        val resp = httpClient.post("$baseUrl/api/session") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }
}
