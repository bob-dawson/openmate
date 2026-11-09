package com.openmate.core.domain.model

class PromptImage(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

data class MessageFileMeta(
    val mime: String,
    val name: String,
)
