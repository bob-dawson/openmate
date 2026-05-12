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
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
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
    @Named("sse") private val client: Call.Factory,
    private val tokenStore: TokenStore,
    private val logger: SyncSseLogger,
) : SyncSseConnection {
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<SyncNotification> = _notifications

    @Volatile
    override var currentBaseUrl: String? = null
        private set

    private val activeCall = AtomicReference<Call?>(null)

    override suspend fun connect(baseUrl: String) {
        if (currentBaseUrl == baseUrl) {
            Log.d("SyncSseClient", "connect skipped: already connected to $baseUrl")
            return
        }
        Log.d("SyncSseClient", "connect: new=$baseUrl old=$currentBaseUrl")
        disconnect()
        currentBaseUrl = baseUrl
        try {
            withContext(Dispatchers.IO) {
                while (currentBaseUrl == baseUrl) {
                    val traceId = "sse-${System.currentTimeMillis()}"
                    try {
                        val startAt = System.currentTimeMillis()
                        val token = tokenStore.activeToken
                        logger.logConnectStart(traceId = traceId, hasToken = token != null)
                        Log.d("SyncSseClient", "attempting SSE connection, token=${token != null}")
                        val urlBuilder = Request.Builder().url("$baseUrl/api/bridge/sync/events").get()
                        if (token != null) {
                            urlBuilder.header("Authorization", "Bearer $token")
                        }
                        val call = client.newCall(urlBuilder.build())
                        activeCall.set(call)
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            logger.logConnectFailure(traceId, IllegalStateException("http ${response.code}"))
                            Log.w("SyncSseClient", "SSE connect returned ${response.code}")
                            response.close()
                            Thread.sleep(5000)
                            continue
                        }
                        logger.logConnectSuccess(traceId = traceId, costMs = System.currentTimeMillis() - startAt)
                        Log.d("SyncSseClient", "SSE connected successfully")
                        val bodyStream = response.body?.byteStream()
                        if (bodyStream == null) {
                            logger.logStreamClosed(traceId)
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
                                        logger.logNotification(
                                            sessionId = sessionId,
                                            seq = seq,
                                            traceId = "notify-${sessionId}-${seq}",
                                        )
                                        Log.d("SyncSseClient", "notification: session=$sessionId seq=$seq emitted=${_notifications.tryEmit(SyncNotification(sessionId, seq))}")
                                    } catch (e: Exception) {
                                        Log.w("SyncSseClient", "parse error: $data")
                                    }
                                }
                                line = reader.readLine()
                            }
                            if (currentBaseUrl == baseUrl) {
                                logger.logStreamClosed(traceId)
                            }
                        } finally {
                            activeCall.compareAndSet(call, null)
                            reader.close()
                            response.close()
                        }
                    } catch (e: CancellationException) {
                        Log.d("SyncSseClient", "connect cancelled, propagating")
                        throw e
                    } catch (e: Exception) {
                        logger.logConnectFailure(traceId, e)
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

    override fun disconnect(traceId: String?) {
        Log.d("SyncSseClient", "disconnect: currentBaseUrl=$currentBaseUrl")
        logger.logDisconnected(traceId = traceId, currentBaseUrl = currentBaseUrl)
        currentBaseUrl = null
        activeCall.getAndSet(null)?.cancel()
    }
}
