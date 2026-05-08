package com.openmate.core.data.sse

import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.ToolRef
import com.openmate.core.network.SseData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class QuestionEventHandler @Inject constructor() {
    var activeDirectory: String = ""

    private val _questions = MutableSharedFlow<QuestionRequest>(extraBufferCapacity = 16)
    val questions: SharedFlow<QuestionRequest> = _questions

    open suspend fun handle(type: String, event: SseData) {
        if (type != "question.asked") return
        val props = event.properties
        val id = props["id"]?.jsonPrimitive?.contentOrNull ?: return
        val sessionID = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
        val questionsArr = props["questions"]?.jsonArray ?: return
        val toolObj = props["tool"]?.jsonObject
        val tool = toolObj?.let {
            ToolRef(
                messageID = it["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                callID = it["callID"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        val questionInfos = questionsArr.map { q ->
            val obj = q.jsonObject
            val options = obj["options"]?.jsonArray?.map { o ->
                val oo = o.jsonObject
                QuestionOption(
                    label = oo["label"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = oo["description"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } ?: emptyList()
            QuestionInfo(
                question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                header = obj["header"]?.jsonPrimitive?.contentOrNull ?: "",
                options = options,
                multiple = obj["multiple"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                custom = obj["custom"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
            )
        }
        _questions.emit(QuestionRequest(id = id, sessionID = sessionID, questions = questionInfos, tool = tool))
    }
}
