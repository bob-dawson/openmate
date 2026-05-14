package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseData
import com.openmate.core.network.SyncApiClient
import com.openmate.core.network.dto.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class SessionEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
    private val retryStateStore: SessionRetryStateStore,
    private val api: OpencodeApiClient,
    private val syncApiClient: SyncApiClient,
    private val logStore: SyncLogStore,
) {
    suspend fun handle(type: String, event: SseData) {
        val props = event.properties
        val sessionID = props["sessionID"]?.jsonPrimitive?.content ?: return

        when (type) {
            "session.created" -> {
                try {
                    val db = dbProvider.getActive()
                    val existing = db.sessionDao().getById(sessionID)
                    if (existing != null) return
                    val info = props["info"]?.jsonObject
                    val title = info?.get("title")?.jsonPrimitive?.content ?: ""
                    val directory = info?.get("directory")?.jsonPrimitive?.content ?: ""
                    val projectID = info?.get("projectID")?.jsonPrimitive?.content ?: ""
                    val parentID = info?.get("parentID")?.jsonPrimitive?.content
                    val time = info?.get("time")?.jsonObject
                    val created = time?.get("created")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val updated = time?.get("updated")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val entity = com.openmate.core.database.entity.SessionEntity(
                        id = sessionID,
                        title = title,
                        directory = directory,
                        projectID = projectID,
                        parentID = parentID,
                        createdAt = created,
                        updatedAt = updated,
                        status = SessionStatus.IDLE.name,
                    )
                    db.sessionDao().upsert(entity)
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.created failed", e)
                }
            }
            "session.updated" -> {
                try {
                    val db = dbProvider.getActive()
                    val existing = db.sessionDao().getById(sessionID) ?: return
                    val info = props["info"]?.jsonObject
                    val title = info?.get("title")?.jsonPrimitive?.content
                    val revertJson = info?.get("revert")
                    val hasRevertKey = info?.contains("revert") == true
                    if (revertJson is JsonObject) {
                        val newRevertMessageID = revertJson["messageID"]?.jsonPrimitive?.content
                        val newRevertPartID = revertJson["partID"]?.jsonPrimitive?.content
                        val evtId = runCatching { syncApiClient.resolveEvtID(sessionID, newRevertMessageID ?: "") }
                            .onFailure {
                                Log.w("SessionEventHandler", "resolveEvtID failed for $newRevertMessageID", it)
                                logStore.log(
                                    level = SyncLogLevel.Error,
                                    category = SyncLogCategory.Sync,
                                    sessionId = sessionID,
                                    title = "SSE revert预解析失败",
                                    message = "msgID=$newRevertMessageID error=${it.javaClass.simpleName}: ${it.message}",
                                )
                            }
                            .getOrNull()
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionID,
                            title = "SSE revert写入",
                            message = "msgID=$newRevertMessageID evtId=$evtId",
                        )
                        db.sessionDao().updateRevertFromSse(
                            id = sessionID,
                            revertMessageID = newRevertMessageID,
                            revertPartID = newRevertPartID,
                            revertFrom = evtId,
                        )
                        if (title != null) {
                            db.sessionDao().updateTitle(sessionID, title)
                        }
                    } else if (hasRevertKey && revertJson is JsonNull) {
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionID,
                            title = "SSE revert清除",
                            message = "revert=null",
                        )
                        db.sessionDao().updateRevertFields(
                            id = sessionID,
                            revertMessageID = null,
                            revertPartID = null,
                            revertFrom = null,
                            revertTo = null,
                        )
                        if (title != null) {
                            db.sessionDao().updateTitle(sessionID, title)
                        }
                    } else {
                        logStore.log(
                            level = SyncLogLevel.Info,
                            category = SyncLogCategory.Sync,
                            sessionId = sessionID,
                            title = "SSE revert跳过",
                            message = "无revert字段，保留现有值",
                        )
                        if (title != null) {
                            db.sessionDao().updateTitle(sessionID, title)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.updated failed", e)
                }
            }
            "session.deleted" -> {
                try {
                    val db = dbProvider.getActive()
                    db.sessionDao().delete(sessionID)
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.deleted failed", e)
                }
            }
            "session.status" -> {
                try {
                    val statusObj = props["status"]?.jsonObject
                    val statusType = statusObj?.get("type")?.jsonPrimitive?.content
                    Log.d("SessionEventHandler", "session.status: sessionID=$sessionID type=$statusType rawStatus=$statusObj")
                    if (statusType == null) {
                        Log.w("SessionEventHandler", "session.status: no type in status object")
                        return
                    }
                    val db = dbProvider.getActive()
                    val status = when (statusType) {
                        "busy" -> SessionStatus.BUSY.name
                        "retry" -> {
                            val message = statusObj["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            retryStateStore.update(
                                sessionID,
                                message?.let {
                                    SessionRetryStatus(
                                        sessionId = sessionID,
                                        attempt = statusObj["attempt"]?.jsonPrimitive?.content?.toIntOrNull(),
                                        message = it,
                                        next = statusObj["next"]?.jsonPrimitive?.content?.toLongOrNull(),
                                    )
                                },
                            )
                            SessionStatus.BUSY.name
                        }
                        else -> {
                            retryStateStore.update(sessionID, null)
                            SessionStatus.IDLE.name
                        }
                    }
                    if (statusType == "busy") {
                        retryStateStore.update(sessionID, null)
                    }
                    val existing = db.sessionDao().getById(sessionID)
                    val now = System.currentTimeMillis()
                    if (existing != null) {
                        val startedAt = if (statusType == "busy" && existing.startedAt == null) now else if (statusType != "busy") null else existing.startedAt
                        db.sessionDao().updateStatusAndStartedAt(sessionID, status, startedAt)
                    } else {
                        try {
                            val dto = api.getSession(sessionID)
                            if (dto.parentID != null) return
                            val domain = dto.toDomain()
                            db.sessionDao().upsert(domain.toEntity().copy(
                                status = status,
                                startedAt = if (statusType == "busy") now else null,
                            ))
                        } catch (e: Exception) {
                            Log.w("SessionEventHandler", "session.status: fetch session failed, skipping", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.status failed", e)
                }
            }
        }
    }
}
