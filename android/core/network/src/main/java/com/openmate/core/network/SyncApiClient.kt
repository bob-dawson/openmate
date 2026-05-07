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
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/events?afterSeq=$afterSeq"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<EventsResponseDto>(body)
        }

    suspend fun fullMessage(sessionId: String, messageId: String): FullMessageResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/message/$messageId/full"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<FullMessageResponseDto>(body)
        }

    suspend fun sessions(): SessionsResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/sessions"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<SessionsResponseDto>(body)
        }
}
