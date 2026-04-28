package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var currentBaseUrl: String? = null
    private var currentAddress: String? = null
    private var currentPort: Int? = null
    private var currentPassword: String? = null
    private var reconnectAttempts = 0
    private val lastEventTime = AtomicLong(0)

    companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
    }

    fun connect(address: String, port: Int, password: String?): Flow<SseData> {
        disconnect()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        currentAddress = address
        currentPort = port
        currentPassword = password
        val baseUrl = "http://$address:$port"
        currentBaseUrl = baseUrl
        reconnectAttempts = 0

        return establishConnection(baseUrl)
    }

    private fun establishConnection(baseUrl: String): Flow<SseData> = flow {
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
                        if (Thread.interrupted()) break
                        val sseData = SseParser.parseLine(line)
                        if (sseData != null) {
                            lastEventTime.set(System.currentTimeMillis())
                            emit(sseData)
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
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
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

        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            if (currentBaseUrl != null && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                val baseUrl = currentBaseUrl ?: return@launch
                connectJob = launch {
                    establishConnection(baseUrl).collect {}
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        stopHeartbeatMonitor()
        currentBaseUrl = null
        currentAddress = null
        currentPort = null
        currentPassword = null
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}
