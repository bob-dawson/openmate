package com.openmate.core.data.repository

import com.openmate.core.data.sse.PermissionEventHandler
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    handler: PermissionEventHandler,
) : PermissionRepository {

    private val _pending = MutableStateFlow<List<PermissionRequest>>(emptyList())
    private val pendingMap = ConcurrentHashMap<String, PermissionRequest>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            handler.permissions.collect { permission ->
                pendingMap[permission.id] = permission
                _pending.value = pendingMap.values.toList()
            }
        }
    }

    override suspend fun refresh(directory: String) {
        try {
            val permissions = api.listPermissions(directory.ifBlank { null })
            val apiIds = permissions.map { it.id }.toSet()
            for (p in permissions) {
                pendingMap[p.id] = p.toDomain()
            }
            pendingMap.keys.retainAll { it in apiIds }
            _pending.value = pendingMap.values.toList()
        } catch (e: Exception) {
            // ignore
        }
    }

    override suspend fun reply(requestID: String, reply: PermissionReply, message: String?, directory: String?) {
        try {
            api.replyPermission(requestID, reply.value, message, directory)
        } catch (e: Exception) {
            pendingMap.remove(requestID)
        } finally {
            _pending.value = pendingMap.values.toList()
        }
    }

    override fun observePending(): Flow<List<PermissionRequest>> = _pending.asStateFlow()
}
