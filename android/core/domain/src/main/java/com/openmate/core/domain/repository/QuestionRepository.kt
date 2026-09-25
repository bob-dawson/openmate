package com.openmate.core.domain.repository

import com.openmate.core.domain.model.QuestionRequest
import kotlinx.coroutines.flow.Flow

interface QuestionRepository {
    suspend fun refresh(sessionID: String, directory: String)
    suspend fun reply(sessionID: String, formID: String, answers: List<List<String>>, directory: String? = null)
    suspend fun reject(sessionID: String, formID: String, directory: String? = null)
    fun observePending(): Flow<List<QuestionRequest>>
    fun clearPending()
}
