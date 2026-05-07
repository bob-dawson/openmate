package com.openmate.core.data.repository

import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.repository.PermissionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class PermissionRepositoryImpl @Inject constructor(
) : PermissionRepository {

    override suspend fun refresh(directory: String) {}

    override suspend fun reply(requestID: String, reply: com.openmate.core.domain.model.PermissionReply, message: String?, directory: String?) {}

    override fun observePending(): Flow<List<PermissionRequest>> = flowOf(emptyList())
}
