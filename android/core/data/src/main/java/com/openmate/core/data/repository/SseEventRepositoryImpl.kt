package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sse.EventDispatcher
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SseEvent
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.SseClient
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
        eventDispatcher.messageSyncEnabled = false
        sseClient.disconnect()
    }

    override fun observeConnectionStatus(): Flow<ConnectionStatus> {
        return sseClient.connectionStatus
    }

    override fun isConnectedTo(address: String, port: Int): Boolean {
        return sseClient.isConnectedTo(address, port)
    }

    override fun setActiveSessionScope(directory: String?, enabled: Boolean) {
        eventDispatcher.activeDirectory = directory.orEmpty()
        eventDispatcher.messageSyncEnabled = enabled
        Log.d("SseEventRepo", "setActiveSessionScope: dir=${directory.orEmpty()} enabled=$enabled")
    }
}
