package com.openmate.core.domain.repository

import com.openmate.core.domain.model.QuestionRequest
import kotlinx.coroutines.flow.Flow

interface QuestionRepository {
    suspend fun getPending(): List<QuestionRequest>
    suspend fun reply(requestID: String, answers: List<List<String>>)
    suspend fun reject(requestID: String)
    fun observePending(): Flow<List<QuestionRequest>>
}
