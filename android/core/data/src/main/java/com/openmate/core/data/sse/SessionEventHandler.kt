package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.network.SseData
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class SessionEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
    private val retryStateStore: SessionRetryStateStore,
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
                    val time = info?.get("time")?.jsonObject
                    val created = time?.get("created")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val updated = time?.get("updated")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val entity = com.openmate.core.database.entity.SessionEntity(
                        id = sessionID,
                        title = title,
                        directory = directory,
                        projectID = projectID,
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
                    val updated = if (title != null) existing.copy(title = title) else existing
                    db.sessionDao().upsert(updated)
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
                    if (existing != null) {
                        db.sessionDao().upsert(existing.copy(status = status))
                    } else {
                        val entity = com.openmate.core.database.entity.SessionEntity(
                            id = sessionID,
                            title = "",
                            directory = "",
                            projectID = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            status = status,
                        )
                        db.sessionDao().upsert(entity)
                    }
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.status failed", e)
                }
            }
        }
    }
}
