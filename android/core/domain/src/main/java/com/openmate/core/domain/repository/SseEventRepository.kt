package com.openmate.core.domain.repository

import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SseEvent
import kotlinx.coroutines.flow.Flow

interface SseEventRepository {
    fun connect(address: String, port: Int, password: String?): Flow<SseEvent>
    fun disconnect()
    fun observeConnectionStatus(): Flow<ConnectionStatus>
    fun isConnectedTo(address: String, port: Int): Boolean
    fun setActiveSessionScope(directory: String?, enabled: Boolean)
}
