package com.openmate.core.network

import android.util.Log
import kotlinx.coroutines.CancellationException
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
    private val tokenStore: TokenStore,
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
        Log.d("SyncSseClient", "connect: baseUrl=$baseUrl")
        try {
            withContext(Dispatchers.IO) {
                while (currentBaseUrl == baseUrl) {
                    try {
                        val token = tokenStore.activeToken
                        Log.d("SyncSseClient", "attempting SSE connection, token=${token != null}")
                        val urlBuilder = Request.Builder().url("$baseUrl/api/bridge/sync/events").get()
                        if (token != null) {
                            urlBuilder.header("Authorization", "Bearer $token")
                        }
                        val response = client.newCall(urlBuilder.build()).execute()
                        if (!response.isSuccessful) {
                            Log.w("SyncSseClient", "SSE connect returned ${response.code}")
                            response.close()
                            Thread.sleep(5000)
                            continue
                        }
                        Log.d("SyncSseClient", "SSE connected successfully")
                        val bodyStream = response.body?.byteStream()
                        if (bodyStream == null) {
                            response.close()
                            Thread.sleep(5000)
                            continue
                        }
                        val reader = BufferedReader(InputStreamReader(bodyStream))
                        try {
                            var line = reader.readLine()
                            while (line != null && currentBaseUrl == baseUrl) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("data:")) {
                                    val data = trimmed.removePrefix("data:").trim()
                                    try {
                                        val jsonObj = json.parseToJsonElement(data).jsonObject
                                        val sessionId = jsonObj["sessionID"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val seq = jsonObj["seq"]?.jsonPrimitive?.longOrNull ?: continue
                                        Log.d("SyncSseClient", "notification: session=$sessionId seq=$seq")
                                        _notifications.tryEmit(SyncNotification(sessionId, seq))
                                    } catch (e: Exception) {
                                        Log.w("SyncSseClient", "parse error: $data")
                                    }
                                }
                                line = reader.readLine()
                            }
                        } finally {
                            reader.close()
                            response.close()
                        }
                    } catch (e: CancellationException) {
                        Log.d("SyncSseClient", "connect cancelled, propagating")
                        throw e
                    } catch (e: Exception) {
                        Log.w("SyncSseClient", "SSE error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    if (currentBaseUrl == baseUrl) {
                        Thread.sleep(3000)
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d("SyncSseClient", "connect coroutine cancelled")
        }
    }

    fun disconnect() {
        Log.d("SyncSseClient", "disconnect: currentBaseUrl=$currentBaseUrl")
        currentBaseUrl = null
    }
}