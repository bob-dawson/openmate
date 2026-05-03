package com.openmate.core.domain.repository

import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import kotlinx.coroutines.flow.Flow

interface PermissionRepository {
    suspend fun refresh()
    suspend fun reply(requestID: String, reply: PermissionReply, message: String?, directory: String? = null)
    fun observePending(): Flow<List<PermissionRequest>>
}
