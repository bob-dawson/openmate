package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.toDomain
import com.openmate.core.database.entity.toEntity
import javax.inject.Inject

open class QuestionEventHandler @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) {
    var activeDirectory: String = ""

    open suspend fun handle(type: String, event: SseData) {
        when (type) {
            "question.asked" -> {
                try {
                    if (activeDirectory.isBlank()) return
                    val dtos = api.listQuestions(activeDirectory)
                    val dao = dbProvider.getActive().questionDao()
                    dao.deleteAll()
                    dtos.forEach { dao.upsert(it.toDomain().toEntity()) }
                } catch (e: Exception) {
                    Log.w("QuestionEventHandler", "sync failed", e)
                }
            }
            "question.replied", "question.rejected" -> {
                try {
                    val requestID = event.properties["requestID"]?.toString()?.trim('"')
                    if (requestID != null) {
                        dbProvider.getActive().questionDao().delete(requestID)
                    }
                } catch (e: Exception) {
                    Log.w("QuestionEventHandler", "handle failed", e)
                }
            }
        }
    }
}
