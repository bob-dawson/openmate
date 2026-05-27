package com.openmate.core.domain.repository

import com.openmate.core.domain.model.QuestionRequest
import kotlinx.coroutines.flow.Flow

interface QuestionRepository {
    suspend fun refresh(directory: String)
    suspend fun reply(requestID: String, answers: List<List<String>>, directory: String? = null)
    suspend fun reject(requestID: String, directory: String? = null)
    fun observePending(): Flow<List<QuestionRequest>>
    fun clearPending()
}
