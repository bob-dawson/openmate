package com.openmate.core.network

import android.util.Log
import com.openmate.core.domain.model.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Named

class SyncSseClient @Inject constructor(
    @Named("sse") private val client: Call.Factory,
    private val tokenStore: TokenStore,
    private val logger: SyncSseLogger,
) : SyncSseConnection {
    private val _notifications = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    val notifications: SharedFlow<BridgeEvent> = _notifications
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    @Volatile
    override var currentBaseUrl: String? = null
        private set

    @Volatile
    var instanceId: String? = null

    private val activeCall = AtomicReference<Call?>(null)

    override suspend fun connect(baseUrl: String) {
        if (currentBaseUrl == baseUrl) {
            Log.d("SyncSseClient", "connect skipped: already connected to $baseUrl")
            return
        }
        Log.d("SyncSseClient", "connect: new=$baseUrl old=$currentBaseUrl")
        disconnect()
        currentBaseUrl = baseUrl
        _connectionStatus.value = ConnectionStatus.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                while (currentBaseUrl == baseUrl) {
                    val traceId = "sse-${System.currentTimeMillis()}"
                    try {
                        val startAt = System.currentTimeMillis()
                        val token = tokenStore.activeToken
                        logger.logConnectStart(traceId = traceId, hasToken = token != null)
                        Log.d("SyncSseClient", "attempting SSE connection, token=${token != null}")
                        val urlBuilder = Request.Builder().url("$baseUrl/api/bridge/events").get()
                        if (token != null) {
                            urlBuilder.header("Authorization", "Bearer $token")
                        }
                        val iid = instanceId
                        if (iid != null) {
                            urlBuilder.header("X-Instance-Id", iid)
                        }
                        val call = client.newCall(urlBuilder.build())
                        activeCall.set(call)
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            _connectionStatus.value = ConnectionStatus.ERROR
                            logger.logConnectFailure(traceId, IllegalStateException("http ${response.code}"))
                            Log.w("SyncSseClient", "SSE connect returned ${response.code}")
                            response.close()
                            Thread.sleep(5000)
                            continue
                        }
                        _connectionStatus.value = ConnectionStatus.CONNECTED
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
                                    val event = BridgeEventParser.parse(data)
                                    if (event != null) {
                                        val traceId = "notify-${event.type}-${event.sessionId ?: "unknown"}"
                                        logger.logNotification(event = event, traceId = traceId)
                                        Log.d(
                                            "SyncSseClient",
                                            "notification: type=${event.type} session=${event.sessionId} message=${event.messageId} part=${event.partId} emitted=${_notifications.tryEmit(event)}"
                                        )
                                    } else {
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
                        _connectionStatus.value = ConnectionStatus.ERROR
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
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        activeCall.getAndSet(null)?.cancel()
    }
}
