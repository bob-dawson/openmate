package com.openmate.core.network.dto

import kotlinx.serialization.SerialName
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
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val modified: Long = 0L,
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
    @SerialName("actual_port")
    val actualPort: Int? = null,
    @SerialName("auth_enabled")
    val authEnabled: Boolean = true,
    @SerialName("instance_id")
    val instanceId: String = "",
)

@Serializable
data class BridgeOpencodeInfoDto(
    val status: String = "unknown",
    val version: String? = null,
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
data class BridgeDeleteRequest(
    val path: String,
    val recursive: Boolean = false,
)

@Serializable
data class BridgeRenameRequest(
    val source: String,
    val destination: String,
)

@Serializable
data class BridgeSearchRequest(
    val path: String,
    val query: String,
    val searchType: String = "filename",
    val maxResults: Int = 50,
    val glob: String? = null,
)

data class BridgeFileContent(
    val content: String,
    val mime: String,
    val isBase64: Boolean,
)

@Serializable
data class PairRequestResponse(
    val pin: String = "",
)

@Serializable
data class PairApproveRequest(
    val pin: String = "",
)

@Serializable
data class PairConfirmRequest(
    val pin: String = "",
)

@Serializable
data class PairConfirmResponse(
    val token: String = "",
)

@Serializable
data class OpencodeVersionResponse(
    val current: String? = null,
    val latest: String? = null,
    @SerialName("hasUpdate")
    val hasUpdate: Boolean = false,
)

@Serializable
data class OpencodeUpgradeResponse(
    val status: String = "",
)

@Serializable
data class OpencodeUpgradeStatusResponse(
    val upgrading: Boolean = false,
)

@Serializable
data class ScanPairConfirmRequest(
    @SerialName("scan_token") val scanToken: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("client_device_id") val clientDeviceId: String = "",
)

@Serializable
data class LoginConfirmRequest(
    @SerialName("session_id") val sessionId: String = "",
)

@Serializable
data class ScanPairConfirmResponse(
    val token: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val name: String = "",
    val address: String = "",
    val port: Int = 0,
)

@Serializable
data class BridgeGitStatusEntry(
    val path: String = "",
    val status: String = "",
    val oldPath: String? = null,
)

@Serializable
data class BridgeUpgradeStatusDto(
    val state: String = "idle",
    val progress: Long = 0,
    val version: String? = null,
    val error: String? = null,
)
