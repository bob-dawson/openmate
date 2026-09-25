package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sse.QuestionEventHandler
import com.openmate.core.domain.model.QuestionInfo
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        CoroutineScope(Dispatchers.IO).launch {
            handler.dismissed.collect { formID ->
                pendingMap.remove(formID)
                _pending.value = pendingMap.values.toList()
            }
        }
    }

    override suspend fun refresh(sessionID: String, directory: String) {
        if (sessionID.isBlank()) return
        try {
            val forms = api.listForms(sessionID, directory.ifBlank { null })
            val apiIds = forms.map { it.id }.toSet()
            for (form in forms) {
                pendingMap[form.id] = form.toDomain()
            }
            pendingMap.keys.retainAll { it in apiIds }
            _pending.value = pendingMap.values.toList()
        } catch (_: Exception) {
        }
    }

    override suspend fun reply(sessionID: String, formID: String, answers: List<List<String>>, directory: String?) {
        val questions = pendingMap[formID]?.questions
        try {
            api.replyForm(sessionID, formID, buildAnswer(answers, questions), directory)
            pendingMap.remove(formID)
        } catch (e: Exception) {
            Log.w("QuestionRepo", "replyForm failed: ${e.message}")
        } finally {
            _pending.value = pendingMap.values.toList()
        }
    }

    override suspend fun reject(sessionID: String, formID: String, directory: String?) {
        try {
            api.cancelForm(sessionID, formID, directory)
            pendingMap.remove(formID)
        } catch (e: Exception) {
            Log.w("QuestionRepo", "cancelForm failed: ${e.message}")
        } finally {
            _pending.value = pendingMap.values.toList()
        }
    }

    override fun observePending(): Flow<List<QuestionRequest>> = _pending.asStateFlow()

    override fun clearPending() {
        pendingMap.clear()
        _pending.value = emptyList()
    }
}

private fun buildAnswer(answers: List<List<String>>, questions: List<QuestionInfo>?): JsonObject = buildJsonObject {
    answers.forEachIndexed { index, values ->
        val key = "q$index"
        val multiple = questions?.getOrNull(index)?.multiple == true
        when {
            values.isEmpty() -> Unit
            multiple -> put(key, buildJsonArray { values.forEach { add(it) } })
            else -> put(key, values.first())
        }
    }
}
