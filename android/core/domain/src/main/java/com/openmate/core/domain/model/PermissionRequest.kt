package com.openmate.core.domain.model

data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: Map<String, String>,
    val always: List<String>,
    val tool: ToolRef? = null,
)

data class ToolRef(
    val messageID: String,
    val callID: String,
)

enum class PermissionReply(val value: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}
