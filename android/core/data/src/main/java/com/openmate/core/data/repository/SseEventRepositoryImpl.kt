package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sse.EventDispatcher
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SseEvent
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.BridgeEvent
import com.openmate.core.network.SseData
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SseEventRepositoryImpl @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val eventDispatcher: EventDispatcher,
) : SseEventRepository {

    private var eventJob: Job? = null
    private var isSubscribed = false

    init {
        ensureSubscribed()
    }

    override fun connect(address: String, port: Int, password: String?): Flow<SseEvent> {
        ensureSubscribed()
        return emptyFlow()
    }

    override fun connectViaGateway(baseUrl: String): Flow<SseEvent> {
        ensureSubscribed()
        return emptyFlow()
    }

    private fun ensureSubscribed() {
        if (!isSubscribed) {
            isSubscribed = true
            eventJob = CoroutineScope(Dispatchers.IO).launch {
                syncSseClient.notifications.collect { event ->
                    eventDispatcher.dispatch(event.toSseData())
                }
            }
        }
    }

    override fun disconnect() {
        eventDispatcher.activeDirectory = ""
        eventDispatcher.messageSyncEnabled = false
        syncSseClient.disconnect("sse-repository")
    }

    override fun observeConnectionStatus(): Flow<ConnectionStatus> {
        return syncSseClient.connectionStatus
    }

    override fun isConnectedTo(address: String, port: Int): Boolean {
        return syncSseClient.currentBaseUrl == "http://$address:$port" &&
            syncSseClient.connectionStatus.value == ConnectionStatus.CONNECTED
    }

    override fun observeMessageSyncNeeded(): Flow<String> {
        return eventDispatcher.messageSyncNeeded
    }

    override fun observeSessionErrors(): Flow<Pair<String, String>> {
        return eventDispatcher.sessionErrors
    }

    override fun setActiveSessionScope(directory: String?, enabled: Boolean) {
        eventDispatcher.activeDirectory = directory.orEmpty()
        eventDispatcher.messageSyncEnabled = enabled
        Log.d("SseEventRepo", "setActiveSessionScope: dir=${directory.orEmpty()} enabled=$enabled")
    }

    private fun BridgeEvent.toSseData(): SseData {
        return SseData(
            type = type,
            properties = properties,
            directory = directory,
        )
    }
}
