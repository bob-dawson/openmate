package com.openmate.core.domain.model

import kotlinx.serialization.Serializable

data class QuestionRequest(
    val id: String,
    val sessionID: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null,
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String,
)
