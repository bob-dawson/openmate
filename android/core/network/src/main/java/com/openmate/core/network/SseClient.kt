package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionStatus
import kotlinx.coroutines.CancellationException
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
    @Volatile private var running = false
    private val lastEventTime = AtomicLong(0)
    private val connectionGeneration = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
    }

    fun connect(address: String, port: Int, password: String?): Flow<SseData> {
        val generation = beginNewConnection()
        val baseUrl = "http://$address:$port"

        startConnection(baseUrl, generation)
        return events
    }

    fun connectViaGateway(baseUrl: String): Flow<SseData> {
        val generation = beginNewConnection()

        startConnection(baseUrl, generation)
        return events
    }

    private fun beginNewConnection(): Long {
        val generation = connectionGeneration.incrementAndGet()
        disconnectInternal(generation)
        updateStatus(ConnectionStatus.CONNECTING, generation)
        return generation
    }

    private fun startConnection(baseUrl: String, generation: Long) {
        connectJob?.cancel()
        connectJob = scope.launch {
            establishConnection(baseUrl, generation)
        }
    }

    private suspend fun establishConnection(baseUrl: String, generation: Long) {
        running = true
        val request = Request.Builder()
            .url("$baseUrl/global/event")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                updateStatus(ConnectionStatus.ERROR, generation)
                throw ServerUnavailableException("SSE connection failed: HTTP ${response.code}")
            }
            updateStatus(ConnectionStatus.CONNECTED, generation)
            lastEventTime.set(System.currentTimeMillis())
            startHeartbeatMonitor(generation)

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!running || Thread.interrupted()) break
                        if (line.startsWith("data:")) {
                            android.util.Log.d("SseClient", "raw: ${line.take(200)}")
                        }
                        lastEventTime.set(System.currentTimeMillis())
                        val sseData = SseParser.parseLine(line)
                        if (sseData != null) {
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
            if (e is CancellationException) throw e
            updateStatus(ConnectionStatus.ERROR, generation)
        }
    }

    private fun startHeartbeatMonitor(generation: Long) {
        stopHeartbeatMonitor()
        heartbeatJob = scope.launch {
            while (coroutineContext[Job]?.isActive == true) {
                delay(HEARTBEAT_TIMEOUT_MS)
                val elapsed = System.currentTimeMillis() - lastEventTime.get()
                if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                    updateStatus(ConnectionStatus.ERROR, generation)
                    connectJob?.cancel()
                    break
                }
            }
        }
    }

    private fun stopHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        disconnectInternal(connectionGeneration.get())
    }

    private fun disconnectInternal(generation: Long) {
        running = false
        connectJob?.cancel()
        connectJob = null
        stopHeartbeatMonitor()
        updateStatus(ConnectionStatus.DISCONNECTED, generation)
    }

    private fun updateStatus(status: ConnectionStatus, generation: Long) {
        if (generation != connectionGeneration.get()) return
        _connectionStatus.value = status
    }

    fun isConnectedTo(address: String, port: Int): Boolean {
        return _connectionStatus.value == ConnectionStatus.CONNECTED
    }
}
