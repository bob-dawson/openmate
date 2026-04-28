package com.openmate.core.data.repository

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class QuestionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : QuestionRepository {

    override suspend fun getPending(): List<QuestionRequest> {
        val dtos = api.listQuestions()
        val dao = dbProvider.getActive().questionDao()
        dtos.forEach { dao.upsert(it.toDomain().toEntity()) }
        return dao.getAll().map { it.toDomain() }
    }

    override suspend fun reply(requestID: String, answers: List<List<String>>) {
        api.replyQuestion(requestID, answers)
        dbProvider.getActive().questionDao().delete(requestID)
    }

    override suspend fun reject(requestID: String) {
        api.rejectQuestion(requestID)
        dbProvider.getActive().questionDao().delete(requestID)
    }

    override fun observePending(): Flow<List<QuestionRequest>> {
        return dbProvider.getActive().questionDao().observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }
}
