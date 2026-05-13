package com.openmate.syncdebugger

import com.openmate.syncdebugger.model.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json

class BridgeClient(private val baseUrl: String, token: String? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(CIO) {
        if (token != null) {
            defaultRequest {
                headers.append("Authorization", "Bearer $token")
            }
        }
    }

    suspend fun getEvents(sessionId: String, afterSeq: Long, limit: Int = 100): EventsResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/session/$sessionId/events?afterSeq=$afterSeq&limit=$limit").bodyAsText()
        return json.decodeFromString<EventsResponseDto>(body)
    }

    suspend fun getInit(sessionId: String, limit: Int = 30): InitResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/session/$sessionId/init?limit=$limit").bodyAsText()
        return json.decodeFromString<InitResponseDto>(body)
    }

    suspend fun getSessions(): SessionsResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/sessions").bodyAsText()
        return json.decodeFromString<SessionsResponseDto>(body)
    }

    fun close() = client.close()
}
