package com.openmate.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Named

data class SyncNotification(
    val sessionId: String,
    val seq: Long,
)

class SyncSseClient @Inject constructor(
    @Named("sse") private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<SyncNotification> = _notifications

    @Volatile
    private var currentBaseUrl: String? = null

    suspend fun connect(baseUrl: String) {
        if (currentBaseUrl == baseUrl) return
        disconnect()
        currentBaseUrl = baseUrl
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/bridge/sync/events"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@withContext))
                reader.useLines { lines ->
                    for (line in lines) {
                        if (currentBaseUrl == null) break
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val data = trimmed.removePrefix("data:").trim()
                            val jsonObj = json.parseToJsonElement(data).jsonObject
                            val sessionId = jsonObj["sessionID"]?.jsonPrimitive?.contentOrNull ?: continue
                            val seq = jsonObj["seq"]?.jsonPrimitive?.longOrNull ?: continue
                            _notifications.tryEmit(SyncNotification(sessionId, seq))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun disconnect() {
        currentBaseUrl = null
    }
}
