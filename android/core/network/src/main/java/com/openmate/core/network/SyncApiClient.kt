package com.openmate.core.network

import com.openmate.core.network.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

class SyncApiClient @Inject constructor(
    @param:Named("api") private val client: OkHttpClient,
    private val opencodeApiClient: OpencodeApiClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val baseUrl: String get() = opencodeApiClient.baseUrl

    suspend fun init(sessionId: String, limit: Int = 30): InitResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/init?limit=$limit"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<InitResponseDto>(body)
        }

    suspend fun events(sessionId: String, afterSeq: Long): EventsResponseDto =
        eventsPayload(sessionId, afterSeq).response

    suspend fun eventsPayload(sessionId: String, afterSeq: Long): EventsPayloadDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/events?afterSeq=$afterSeq"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            EventsPayloadDto(
                response = json.decodeFromString<EventsResponseDto>(body),
                rawBody = body,
                rawEventBodies = extractRawEventBodies(body),
            )
        }

    suspend fun sessions(): SessionsResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/sessions"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<SessionsResponseDto>(body)
        }

    suspend fun full(sessionId: String, messageId: String): FullMessageResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/message/$messageId/full"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<FullMessageResponseDto>(body)
        }
}

private fun extractRawEventBodies(rawBody: String): List<String> {
    val eventsKeyIndex = rawBody.indexOf("\"events\"")
    if (eventsKeyIndex < 0) return emptyList()

    val arrayStart = rawBody.indexOf('[', startIndex = eventsKeyIndex)
    if (arrayStart < 0) return emptyList()

    val result = mutableListOf<String>()
    var inString = false
    var escaped = false
    var braceDepth = 0
    var objectStart = -1

    for (index in arrayStart + 1 until rawBody.length) {
        val ch = rawBody[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }

        when (ch) {
            '"' -> inString = true
            '{' -> {
                if (braceDepth == 0) objectStart = index
                braceDepth += 1
            }
            '}' -> {
                if (braceDepth == 0) continue
                braceDepth -= 1
                if (braceDepth == 0 && objectStart >= 0) {
                    result += rawBody.substring(objectStart, index + 1)
                    objectStart = -1
                }
            }
            ']' -> if (braceDepth == 0) break
        }
    }

    return result
}
