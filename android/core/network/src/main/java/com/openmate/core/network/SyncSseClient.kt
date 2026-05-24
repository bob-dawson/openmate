package com.openmate.core.network

import android.util.Log
import com.openmate.core.domain.model.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
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
    private val _transportSignals = MutableSharedFlow<SyncSseSignal>(extraBufferCapacity = 32)
    val transportSignals: SharedFlow<SyncSseSignal> = _transportSignals
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    @Volatile
    override var currentBaseUrl: String? = null
        private set

    @Volatile
    var instanceId: String? = null

    private val activeCall = AtomicReference<Call?>(null)
    private val connectionGeneration = AtomicLong(0)

    override suspend fun connect(baseUrl: String, forceRestart: Boolean) {
        if (!forceRestart && currentBaseUrl == baseUrl) {
            Log.d("SyncSseClient", "connect skipped: already connected to $baseUrl")
            return
        }
        Log.d("SyncSseClient", "connect: new=$baseUrl old=$currentBaseUrl")
        disconnect()
        val generation = connectionGeneration.incrementAndGet()
        currentBaseUrl = baseUrl
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _transportSignals.tryEmit(SyncSseSignal.ConnectStarted(baseUrl))
        try {
            withContext(Dispatchers.IO) {
                while (currentBaseUrl == baseUrl && connectionGeneration.get() == generation) {
                    currentCoroutineContext().ensureActive()
                    val traceId = "sse-${System.currentTimeMillis()}"
                    try {
                        val startAt = System.currentTimeMillis()
                        val token = tokenStore.activeToken
                        logger.logConnectStart(traceId = traceId, hasToken = token != null)
                        Log.d("SyncSseClient", "attempting SSE connection, token=${token != null}")
                        val urlBuilder = Request.Builder().url("$baseUrl/api/bridge/events").get()
                        val call = client.newCall(urlBuilder.build())
                        activeCall.set(call)
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            if (connectionGeneration.get() != generation) {
                                Log.d("SyncSseClient", "ignoring http error from stale generation: ${response.code}")
                                response.close()
                            } else {
                                _connectionStatus.value = ConnectionStatus.ERROR
                                _transportSignals.tryEmit(SyncSseSignal.Failed(baseUrl, "http ${response.code}"))
                                logger.logConnectFailure(traceId, IllegalStateException("http ${response.code}"))
                                Log.w("SyncSseClient", "SSE connect returned ${response.code}")
                                response.close()
                                delay(5000)
                            }
                            continue
                        }
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _transportSignals.tryEmit(SyncSseSignal.Connected(baseUrl))
                        logger.logConnectSuccess(traceId = traceId, costMs = System.currentTimeMillis() - startAt)
                        Log.d("SyncSseClient", "SSE connected successfully")
                        val bodyStream = response.body?.byteStream()
                        if (bodyStream == null) {
                            logger.logStreamClosed(traceId)
                            response.close()
                            delay(5000)
                            continue
                        }
                        val reader = BufferedReader(InputStreamReader(bodyStream))
                        try {
                            var line = reader.readLine()
                            while (line != null && currentBaseUrl == baseUrl && connectionGeneration.get() == generation) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("data:")) {
                                    val data = trimmed.removePrefix("data:").trim()
                                    val event = BridgeEventParser.parse(data)
                                    if (event != null) {
                                        _transportSignals.tryEmit(SyncSseSignal.EventReceived(baseUrl))
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
                            if (currentBaseUrl == baseUrl && connectionGeneration.get() == generation) {
                                _transportSignals.tryEmit(SyncSseSignal.StreamClosed(baseUrl))
                                logger.logStreamClosed(traceId)
                            } else {
                                Log.d("SyncSseClient", "ignoring stream closed from stale generation")
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
                        if (connectionGeneration.get() != generation) {
                            Log.d("SyncSseClient", "ignoring error from stale generation: ${e.javaClass.simpleName}")
                        } else {
                            _connectionStatus.value = ConnectionStatus.ERROR
                            _transportSignals.tryEmit(SyncSseSignal.Failed(baseUrl, e.message))
                            logger.logConnectFailure(traceId, e)
                            Log.w("SyncSseClient", "SSE error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    if (currentBaseUrl == baseUrl && connectionGeneration.get() == generation) {
                        delay(3000)
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d("SyncSseClient", "connect coroutine cancelled")
            throw e
        }
    }

    override fun disconnect(traceId: String?) {
        Log.d("SyncSseClient", "disconnect: currentBaseUrl=$currentBaseUrl")
        logger.logDisconnected(traceId = traceId, currentBaseUrl = currentBaseUrl)
        connectionGeneration.incrementAndGet()
        currentBaseUrl = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        activeCall.getAndSet(null)?.cancel()
    }
}
