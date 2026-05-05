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

    override suspend fun refresh(directory: String) {
        val dtos = api.listQuestions(directory)
        val dao = dbProvider.getActive().questionDao()
        dao.deleteAll()
        dtos.forEach { dao.upsert(it.toDomain().toEntity()) }
    }

    override suspend fun reply(requestID: String, answers: List<List<String>>, directory: String?) {
        api.replyQuestion(requestID, answers, directory)
        dbProvider.getActive().questionDao().delete(requestID)
    }

    override suspend fun reject(requestID: String, directory: String?) {
        api.rejectQuestion(requestID, directory)
        dbProvider.getActive().questionDao().delete(requestID)
    }

    override fun observePending(): Flow<List<QuestionRequest>> {
        return dbProvider.getActive().questionDao().observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }
}
