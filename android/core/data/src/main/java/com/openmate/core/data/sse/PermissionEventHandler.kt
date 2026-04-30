package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.toDomain
import com.openmate.core.database.entity.toEntity
import javax.inject.Inject

open class PermissionEventHandler @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) {
    open suspend fun handle(type: String, event: SseData) {
        when (type) {
            "permission.asked" -> {
                try {
                    val dtos = api.listPermissions()
                    val dao = dbProvider.getActive().permissionDao()
                    dao.deleteAll()
                    dtos.forEach { dao.upsert(it.toDomain().toEntity()) }
                } catch (e: Exception) {
                    Log.w("PermissionEventHandler", "sync failed", e)
                }
            }
            "permission.replied" -> {
                try {
                    val requestID = event.properties["requestID"]?.toString()?.trim('"')
                    if (requestID != null) {
                        dbProvider.getActive().permissionDao().delete(requestID)
                    }
                } catch (e: Exception) {
                    Log.w("PermissionEventHandler", "replied failed", e)
                }
            }
        }
    }
}
