package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.SseData
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class SessionEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
) {
    suspend fun handle(type: String, event: SseData) {
        val props = event.properties
        val sessionID = props["sessionID"]?.jsonPrimitive?.content ?: return

        when (type) {
            "session.updated" -> {
                try {
                    val db = dbProvider.getActive()
                    val existing = db.sessionDao().getById(sessionID) ?: return
                    val title = props["title"]?.jsonPrimitive?.content
                    if (title != null) {
                        db.sessionDao().upsert(existing.copy(title = title))
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
                    val status = props["status"]?.jsonPrimitive?.content ?: return
                    val db = dbProvider.getActive()
                    val existing = db.sessionDao().getById(sessionID) ?: return
                    db.sessionDao().upsert(existing.copy(status = status.uppercase()))
                } catch (e: Exception) {
                    Log.w("SessionEventHandler", "session.status failed", e)
                }
            }
        }
    }
}
