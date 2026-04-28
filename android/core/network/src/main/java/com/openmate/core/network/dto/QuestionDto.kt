package com.openmate.core.network.dto

import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuestionDto(
    val id: String,
    @SerialName("sessionID") val sessionID: String = "",
    val questions: List<QuestionInfoDto> = emptyList(),
    val tool: QuestionToolDto? = null,
)

@Serializable
data class QuestionInfoDto(
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOptionDto> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = true,
)

@Serializable
data class QuestionOptionDto(
    val label: String = "",
    val description: String = "",
)

@Serializable
data class QuestionToolDto(
    @SerialName("messageID") val messageID: String = "",
    @SerialName("callID") val callID: String = "",
)

fun QuestionDto.toDomain(): QuestionRequest {
    return QuestionRequest(
        id = id,
        sessionID = sessionID,
        questions = questions.map { q ->
            QuestionInfo(
                question = q.question,
                header = q.header,
                options = q.options.map { QuestionOption(it.label, it.description) },
                multiple = q.multiple,
                custom = q.custom,
            )
        },
        tool = tool?.let { ToolRef(it.messageID, it.callID) },
    )
}
