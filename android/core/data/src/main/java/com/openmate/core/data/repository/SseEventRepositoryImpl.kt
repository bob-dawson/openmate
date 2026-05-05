package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sse.EventDispatcher
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SseEvent
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseClient
import com.openmate.core.network.SseData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SseEventRepositoryImpl @Inject constructor(
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val apiClient: OpencodeApiClient,
) : SseEventRepository {

    private var eventJob: Job? = null
    private var isSubscribed = false

    override fun connect(address: String, port: Int, password: String?): Flow<SseEvent> {
        if (!isSubscribed) {
            isSubscribed = true
            eventJob = CoroutineScope(Dispatchers.IO).launch {
                sseClient.events.collect { sseData ->
                    eventDispatcher.dispatch(sseData)
                }
            }
        }

        sseClient.connect(address, port, password)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pathInfo = apiClient.getPath()
                eventDispatcher.activeDirectory = pathInfo.directory
                Log.d("SseEventRepo", "activeDirectory set to ${pathInfo.directory}")
            } catch (e: Exception) {
                Log.e("SseEventRepo", "failed to fetch /path", e)
            }
        }

        return sseClient.events.map { sseData ->
            SseEvent(
                type = sseData.type,
                payload = sseData.properties.toString(),
            )
        }
    }

    override fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        isSubscribed = false
        eventDispatcher.activeDirectory = ""
        sseClient.disconnect()
    }

    override fun observeConnectionStatus(): Flow<ConnectionStatus> {
        return sseClient.connectionStatus
    }

    override fun isConnectedTo(address: String, port: Int): Boolean {
        return sseClient.isConnectedTo(address, port)
    }
}
