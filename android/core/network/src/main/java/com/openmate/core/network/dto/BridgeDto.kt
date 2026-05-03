package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class BridgeRootEntryDto(
    val name: String = "",
    val path: String = "",
)

@Serializable
data class BridgeDirEntryDto(
    val name: String = "",
    val type: String = "file",
    val size: Long = 0L,
    val modified: Long = 0L,
    val permissions: String = "",
    val isDirectory: Boolean = false,
)

@Serializable
data class BridgeFileStatDto(
    val name: String = "",
    val type: String = "file",
    val size: Long = 0L,
    val modified: Long = 0L,
    val permissions: String = "",
    val isDirectory: Boolean = false,
)

@Serializable
data class BridgeSearchResultDto(
    val path: String = "",
    val line: Int? = null,
    val column: Int? = null,
    val snippet: String? = null,
)

@Serializable
data class BridgeStatusResponse(
    val bridge: BridgeInfoDto = BridgeInfoDto(),
    val opencode: BridgeOpencodeInfoDto = BridgeOpencodeInfoDto(),
)

@Serializable
data class BridgeInfoDto(
    val version: String = "",
    val port: Int = 0,
)

@Serializable
data class BridgeOpencodeInfoDto(
    val status: String = "unknown",
    val url: String = "",
    val directory: String = "",
)

@Serializable
data class BridgeMkdirRequest(
    val path: String,
    val recursive: Boolean = false,
)

@Serializable
data class BridgeWriteRequest(
    val path: String,
    val content: String,
    val createDirs: Boolean = false,
)

@Serializable
data class BridgeSearchRequest(
    val path: String,
    val query: String,
    val searchType: String = "filename",
    val maxResults: Int = 50,
)

data class BridgeFileContent(
    val content: String,
    val mime: String,
    val isBase64: Boolean,
)
