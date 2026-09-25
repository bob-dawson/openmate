package com.openmate.core.network.dto

import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class FormDto(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val title: String = "",
    val metadata: JsonObject = JsonObject(emptyMap()),
    val fields: List<FormFieldDto> = emptyList(),
)

@Serializable
data class FormFieldDto(
    val key: String = "",
    val type: String = "string",
    val title: String? = null,
    val description: String? = null,
    val options: List<FormOptionDto> = emptyList(),
    val custom: Boolean? = null,
)

@Serializable
data class FormOptionDto(
    val value: String = "",
    val label: String = "",
    val description: String? = null,
)

fun FormDto.toDomain(): QuestionRequest {
    val toolObj = metadata["tool"]?.jsonObject
    val tool = toolObj?.let {
        ToolRef(
            messageID = it["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
            callID = it["id"]?.jsonPrimitive?.contentOrNull
                ?: it["callID"]?.jsonPrimitive?.contentOrNull
                ?: "",
        )
    }
    return QuestionRequest(
        id = id,
        sessionID = sessionID,
        questions = fields.map { f ->
            QuestionInfo(
                question = f.description ?: "",
                header = f.title ?: "",
                options = f.options.map { QuestionOption(it.label, it.description ?: "") },
                multiple = f.type == "multiselect",
                custom = f.custom ?: true,
            )
        },
        tool = tool,
    )
}
