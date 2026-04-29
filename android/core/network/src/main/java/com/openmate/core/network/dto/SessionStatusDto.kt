package com.openmate.core.network.dto

import com.openmate.core.domain.model.SessionStatus
import kotlinx.serialization.Serializable

@Serializable
data class SessionStatusDto(
    val type: String = "idle",
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null,
)

fun SessionStatusDto.toDomain(): SessionStatus {
    return when (type) {
        "busy" -> SessionStatus.BUSY
        "retry" -> SessionStatus.BUSY
        else -> SessionStatus.IDLE
    }
}