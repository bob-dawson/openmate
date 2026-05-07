package com.openmate.core.data.repository

import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.repository.QuestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class QuestionRepositoryImpl @Inject constructor(
) : QuestionRepository {

    override suspend fun refresh(directory: String) {}

    override suspend fun reply(requestID: String, answers: List<List<String>>, directory: String?) {}

    override suspend fun reject(requestID: String, directory: String?) {}

    override fun observePending(): Flow<List<QuestionRequest>> = flowOf(emptyList())
}
