package com.openmate.core.data.repository

import com.openmate.core.data.sse.EventDispatcher
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SseEvent
import com.openmate.core.domain.repository.SseEventRepository
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
) : SseEventRepository {

    private var eventJob: Job? = null

    override fun connect(address: String, port: Int, password: String?): Flow<SseEvent> {
        eventJob?.cancel()
        val eventFlow = sseClient.connect(address, port, password)

        eventJob = CoroutineScope(Dispatchers.IO).launch {
            eventFlow.collect { sseData ->
                eventDispatcher.dispatch(sseData)
            }
        }

        return eventFlow.map { sseData ->
            SseEvent(
                type = sseData.type,
                payload = sseData.properties.toString(),
            )
        }
    }

    override fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        sseClient.disconnect()
    }

    override fun observeConnectionStatus(): Flow<ConnectionStatus> {
        return sseClient.connectionStatus
    }
}
