package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

class SseClient(
    private val client: OkHttpClient,
) {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _events = MutableSharedFlow<SseData>(extraBufferCapacity = 64)
    val events: Flow<SseData> = _events.asSharedFlow()

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var currentBaseUrl: String? = null
    private var reconnectAttempts = 0
    private val lastEventTime = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var running = false

    companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
    }

    fun connect(address: String, port: Int, password: String?): Flow<SseData> {
        disconnect()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val baseUrl = "http://$address:$port"
        currentBaseUrl = baseUrl
        reconnectAttempts = 0

        startConnection(baseUrl)
        return events
    }

    private fun startConnection(baseUrl: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            establishConnection(baseUrl)
        }
    }

    private suspend fun establishConnection(baseUrl: String) {
        running = true
        val request = Request.Builder()
            .url("$baseUrl/global/event")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                _connectionStatus.value = ConnectionStatus.ERROR
                throw ServerUnavailableException("SSE connection failed: HTTP ${response.code}")
            }
            _connectionStatus.value = ConnectionStatus.CONNECTED
            reconnectAttempts = 0
            lastEventTime.set(System.currentTimeMillis())
            startHeartbeatMonitor()

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!running || Thread.interrupted()) break
                        if (line.startsWith("data:")) {
                            android.util.Log.d("SseClient", "raw: ${line.take(200)}")
                        }
                        val sseData = SseParser.parseLine(line)
                        if (sseData != null) {
                            lastEventTime.set(System.currentTimeMillis())
                            android.util.Log.d("SseClient", "parsed: type=${sseData.type}")
                            _events.emit(sseData)
                        }
                    }
                }
            } finally {
                stopHeartbeatMonitor()
                response.close()
            }
        } catch (e: Exception) {
            stopHeartbeatMonitor()
            if (e is kotlinx.coroutines.CancellationException) throw e
            _connectionStatus.value = ConnectionStatus.ERROR
            scheduleReconnect()
        }
    }

    private fun startHeartbeatMonitor() {
        stopHeartbeatMonitor()
        heartbeatJob = scope.launch {
            while (coroutineContext[Job]?.isActive == true) {
                delay(HEARTBEAT_TIMEOUT_MS)
                val elapsed = System.currentTimeMillis() - lastEventTime.get()
                if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    connectJob?.cancel()
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun stopHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        if (currentBaseUrl == null) return
        val delayMs = (BASE_RECONNECT_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(4)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempts++

        scope.launch {
            delay(delayMs)
            if (currentBaseUrl != null) {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                startConnection(currentBaseUrl!!)
            }
        }
    }

    fun disconnect() {
        running = false
        connectJob?.cancel()
        connectJob = null
        stopHeartbeatMonitor()
        currentBaseUrl = null
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun isConnectedTo(address: String, port: Int): Boolean {
        return currentBaseUrl == "http://$address:$port" && _connectionStatus.value == ConnectionStatus.CONNECTED
    }
}
