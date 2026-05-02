package com.openmate.core.data.repository

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PermissionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : PermissionRepository {

    override suspend fun refresh() {
        val dtos = api.listPermissions()
        val dao = dbProvider.getActive().permissionDao()
        dao.replaceAll(dtos.map { it.toDomain().toEntity() })
    }

    override suspend fun reply(requestID: String, reply: PermissionReply, message: String?) {
        dbProvider.getActive().permissionDao().delete(requestID)
        api.replyPermission(requestID, reply.value, message)
    }

    override fun observePending(): Flow<List<PermissionRequest>> {
        return dbProvider.getActive().permissionDao().observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }
}
