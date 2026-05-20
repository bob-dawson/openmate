package com.openmate.core.data.repository

import com.openmate.core.data.sse.QuestionEventHandler
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    handler: QuestionEventHandler,
) : QuestionRepository {

    private val _pending = MutableStateFlow<List<QuestionRequest>>(emptyList())
    private val pendingMap = ConcurrentHashMap<String, QuestionRequest>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            handler.questions.collect { question ->
                pendingMap[question.id] = question
                _pending.value = pendingMap.values.toList()
            }
        }
    }

    override suspend fun refresh(directory: String) {
        try {
            val questions = api.listQuestions(directory.ifBlank { null })
            val apiIds = questions.map { it.id }.toSet()
            for (q in questions) {
                pendingMap[q.id] = q.toDomain()
            }
            pendingMap.keys.retainAll { it in apiIds }
            _pending.value = pendingMap.values.toList()
        } catch (e: Exception) {
            // ignore
        }
    }

    override suspend fun reply(requestID: String, answers: List<List<String>>, directory: String?) {
        try {
            api.replyQuestion(requestID, answers, directory)
            pendingMap.remove(requestID)
        } catch (e: Exception) {
            pendingMap.remove(requestID)
        } finally {
            _pending.value = pendingMap.values.toList()
        }
    }

    override suspend fun reject(requestID: String, directory: String?) {
        try {
            api.rejectQuestion(requestID, directory)
            pendingMap.remove(requestID)
        } catch (e: Exception) {
            pendingMap.remove(requestID)
        } finally {
            _pending.value = pendingMap.values.toList()
        }
    }

    override fun observePending(): Flow<List<QuestionRequest>> = _pending.asStateFlow()
}
