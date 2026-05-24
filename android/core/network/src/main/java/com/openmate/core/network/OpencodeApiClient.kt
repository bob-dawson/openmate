package com.openmate.core.network

import android.util.Log
import com.openmate.core.network.dto.AgentDto
import com.openmate.core.network.dto.BridgeDeleteRequest
import com.openmate.core.network.dto.BridgeDirEntryDto
import com.openmate.core.network.dto.BridgeFileContent
import com.openmate.core.network.dto.BridgeFileStatDto
import com.openmate.core.network.dto.BridgeMkdirRequest
import com.openmate.core.network.dto.BridgeRenameRequest
import com.openmate.core.network.dto.BridgeRootEntryDto
import com.openmate.core.network.dto.BridgeSearchRequest
import com.openmate.core.network.dto.BridgeSearchResultDto
import com.openmate.core.network.dto.BridgeStatusResponse
import com.openmate.core.network.dto.BridgeWriteRequest
import com.openmate.core.network.dto.FileNodeDto
import com.openmate.core.network.dto.HealthDto
import com.openmate.core.network.dto.McpServerEntry
import com.openmate.core.network.dto.MessageHeaderDto
import com.openmate.core.network.dto.OpencodeUpgradeResponse
import com.openmate.core.network.dto.OpencodeUpgradeStatusResponse
import com.openmate.core.network.dto.OpencodeVersionResponse
import com.openmate.core.network.dto.PairConfirmRequest
import com.openmate.core.network.dto.PairConfirmResponse
import com.openmate.core.network.dto.PairRequestResponse
import com.openmate.core.network.dto.ScanPairConfirmRequest
import com.openmate.core.network.dto.ScanPairConfirmResponse
import com.openmate.core.network.dto.PermissionDto
import com.openmate.core.network.dto.ProviderListDto
import com.openmate.core.network.dto.QuestionDto
import com.openmate.core.network.dto.SessionDto
import com.openmate.core.network.dto.PathInfo
import com.openmate.core.network.dto.SessionStatusDto
import com.openmate.core.network.dto.SkillInfoDto
import com.openmate.core.domain.model.ConnectionRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrl

class OpencodeApiClient(
    private val client: OkHttpClient,
    private val downloadClient: OkHttpClient = client,
    var baseUrl: String = "http://localhost:8080",
    private val gatewayInterceptor: GatewayInterceptor? = null,
    private val routeEvidenceReporter: RouteEvidenceReporter? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class MessageHeadersPage(
        val items: List<MessageHeaderDto>,
        val nextCursor: String?,
    )

    suspend fun listSessions(directory: String?, limit: Int?, start: Long?): List<SessionDto> {
        val params = mutableMapOf<String, String>()
        params["roots"] = "true"
        directory?.let { params["directory"] = it }
        limit?.let { params["limit"] = it.toString() }
        start?.let { params["start"] = it.toString() }
        return getList("/experimental/session", params)
    }

    suspend fun getSession(id: String): SessionDto {
        return get("/session/$id")
    }

    suspend fun createSession(title: String? = null, directory: String? = null): SessionDto {
        val body = mutableMapOf<String, String>()
        title?.let { body["title"] = it }
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        return post("/session", body, params)
    }

    suspend fun deleteSession(id: String) {
        delete("/session/$id")
    }

    suspend fun updateSession(id: String, title: String? = null) {
        val body = mutableMapOf<String, String>()
        title?.let { body["title"] = it }
        patch("/session/$id", body)
    }

    suspend fun getMessageHeaders(sessionID: String, limit: Int, before: String?, directory: String? = null): MessageHeadersPage {
        val params = mutableMapOf<String, String>()
        params["limit"] = limit.toString()
        before?.let { params["before"] = it }
        directory?.let { params["directory"] = it }
        val request = requestBuilder("GET", "/session/$sessionID/message", params)
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return MessageHeadersPage(emptyList(), null)
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        val items: List<MessageHeaderDto> = json.decodeFromString(body)
        val nextCursor = response.header("X-Next-Cursor")
        return MessageHeadersPage(items, nextCursor)
    }

    data class FileAttachment(val path: String, val filename: String, val mime: String)

    suspend fun sendPrompt(
        sessionID: String,
        content: String,
        providerID: String? = null,
        modelID: String? = null,
        agent: String? = null,
        files: List<FileAttachment> = emptyList(),
        directory: String? = null,
        variant: String? = null,
    ) {
        val textParts = listOf(
            mapOf("type" to "text", "text" to content)
        )
        val fileParts = files.map { f ->
            mapOf(
                "type" to "file",
                "mime" to f.mime,
                "url" to "file://${f.path}",
                "filename" to f.filename,
                "source" to mapOf(
                    "type" to "file",
                    "path" to f.path,
                    "text" to mapOf("value" to "", "start" to 0, "end" to 0),
                ),
            )
        }
        val parts = mapOf("parts" to textParts + fileParts)
        val extraFields = mutableMapOf<String, Any>()
        if (providerID != null && modelID != null) {
            extraFields["model"] = JsonObject(mapOf(
                "providerID" to JsonPrimitive(providerID),
                "modelID" to JsonPrimitive(modelID),
            ))
        }
        if (agent != null) {
            extraFields["agent"] = agent
        }
        if (variant != null) {
            extraFields["variant"] = variant
        }
        val bodyMap = parts + extraFields
        val body = mapToJson(bodyMap)
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/prompt_async", body, params)
    }

    suspend fun abortSession(sessionID: String, directory: String? = null) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/abort", emptyMap<String, String>(), params)
    }

    suspend fun revertSession(sessionID: String, messageID: String, partID: String? = null, directory: String? = null) {
        val body = mutableMapOf<String, String>()
        body["messageID"] = messageID
        partID?.let { body["partID"] = it }
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/revert", body, params)
    }

    suspend fun unrevertSession(sessionID: String, directory: String? = null) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/unrevert", emptyMap<String, String>(), params)
    }

    suspend fun listPermissions(directory: String? = null): List<PermissionDto> {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        return getList("/permission", params)
    }

    suspend fun replyPermission(requestID: String, reply: String, message: String?, directory: String? = null) {
        val body = mutableMapOf<String, String>()
        body["reply"] = reply
        message?.let { body["message"] = it }
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/permission/$requestID/reply", body, params)
    }

    suspend fun listQuestions(directory: String? = null): List<QuestionDto> {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        return getList("/question", params)
    }

    suspend fun replyQuestion(requestID: String, answers: List<List<String>>, directory: String? = null) {
        val body = mapOf("answers" to answers)
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/question/$requestID/reply", body, params)
    }

    suspend fun rejectQuestion(requestID: String, directory: String? = null) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/question/$requestID/reject", emptyMap<String, String>(), params)
    }

    suspend fun getSessionStatuses(directory: String? = null): Map<String, SessionStatusDto> {
        val params = directory?.let { mapOf("directory" to it) } ?: emptyMap()
        return get("/session/status", params)
    }

    suspend fun getTodos(sessionID: String, directory: String? = null): List<com.openmate.core.domain.model.TodoInfo> {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        val url = buildUrl("/session/$sessionID/todo", params)
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        return json.decodeFromString(body)
    }

    suspend fun healthCheck(): HealthDto {
        return get("/global/health")
    }

    suspend fun getPath(): PathInfo {
        return get("/path")
    }

    suspend fun listFiles(path: String): List<FileNodeDto> {
        return getList("/file", mapOf("path" to path))
    }

    suspend fun searchFiles(query: String, limit: Int = 20): List<String> {
        return getList("/find/file", mapOf("query" to query, "limit" to limit.toString(), "type" to "file"))
    }

    suspend fun getProviders(): ProviderListDto {
        return get("/provider")
    }

    suspend fun getAgents(): List<AgentDto> {
        return getList("/agent")
    }

    suspend fun getSkills(): List<SkillInfoDto> {
        return getList("/skill")
    }

    suspend fun getMcpStatus(directory: String? = null): List<McpServerEntry> = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        val request = requestBuilder("GET", "/mcp", params).get().build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) {
                throw ServerUnavailableException("HTTP ${response.code}: $body")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            obj.entries.map { (name, element) ->
                val statusObj = element.jsonObject
                McpServerEntry(
                    name = name,
                    status = statusObj["status"]?.jsonPrimitive?.content ?: "unknown",
                    error = statusObj["error"]?.jsonPrimitive?.content,
                )
            }
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    suspend fun connectMcp(name: String, directory: String? = null) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/mcp/$name/connect", emptyMap<String, String>(), params)
    }

    suspend fun disconnectMcp(name: String, directory: String? = null) {
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/mcp/$name/disconnect", emptyMap<String, String>(), params)
    }

    suspend fun summarizeSession(sessionID: String, providerID: String, modelID: String, directory: String? = null) {
        val body = mapOf(
            "providerID" to providerID,
            "modelID" to modelID,
        )
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/summarize", body, params)
    }

    suspend fun initSession(sessionID: String, providerID: String, modelID: String, messageID: String, directory: String? = null) {
        val body = mapOf(
            "providerID" to providerID,
            "modelID" to modelID,
            "messageID" to messageID,
        )
        val params = mutableMapOf<String, String>()
        directory?.let { params["directory"] = it }
        postUnit("/session/$sessionID/init", body, params)
    }

    suspend fun bridgeListRoots(): List<BridgeRootEntryDto> {
        return getList("/api/bridge/fs/roots")
    }

    suspend fun bridgeListDir(path: String): List<BridgeDirEntryDto> {
        return getList("/api/bridge/fs/list", mapOf("path" to path))
    }

    suspend fun bridgeStat(path: String): BridgeFileStatDto {
        return get("/api/bridge/fs/stat", mapOf("path" to path))
    }

    suspend fun bridgeReadFile(path: String): BridgeFileContent {
        val url = buildUrl("/api/bridge/fs/read", mapOf("path" to path))
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
        val mime = response.header("content-type", "text/plain") ?: "text/plain"
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("yaml") || mime.contains("markdown") || mime.contains("toml")) {
            val body = response.body?.string() ?: ""
            return BridgeFileContent(content = body, mime = mime, isBase64 = false)
        }
        val body = response.body?.string() ?: ""
        if (body.startsWith("{")) {
            try {
                val element = Json.parseToJsonElement(body)
                val obj = element.jsonObject
                val encoding = obj["encoding"]?.jsonPrimitive?.content
                val fileMime = obj["mime"]?.jsonPrimitive?.content ?: "application/octet-stream"
                if (encoding == "base64") {
                    val data = obj["data"]?.jsonPrimitive?.content ?: ""
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    return BridgeFileContent(content = String(bytes, Charsets.UTF_8), mime = fileMime, isBase64 = true)
                }
            } catch (_: Exception) {}
        }
        return BridgeFileContent(content = body, mime = mime, isBase64 = false)
    }

    suspend fun bridgeWriteFile(path: String, content: String, createDirs: Boolean = false) {
        val body = BridgeWriteRequest(path, content, createDirs)
        val jsonStr = json.encodeToString(BridgeWriteRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/fs/write", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    suspend fun bridgeUploadFile(path: String, bytes: ByteArray, createDirs: Boolean = true) {
        val params = mapOf("path" to path, "createDirs" to createDirs.toString())
        val url = buildUrl("/api/bridge/fs/upload", params)
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url(url).put(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("Upload failed: HTTP ${response.code}")
        }
    }

    suspend fun bridgeMkdir(path: String, recursive: Boolean = true) {
        val body = BridgeMkdirRequest(path, recursive)
        val jsonStr = json.encodeToString(BridgeMkdirRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/fs/mkdir", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    suspend fun bridgeDelete(path: String, recursive: Boolean = false) {
        val body = BridgeDeleteRequest(path, recursive)
        val jsonStr = json.encodeToString(BridgeDeleteRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/fs/delete", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("Delete failed: HTTP ${response.code}")
        }
    }

    suspend fun bridgeRename(source: String, destination: String) {
        val body = BridgeRenameRequest(source, destination)
        val jsonStr = json.encodeToString(BridgeRenameRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/fs/rename", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("Rename failed: HTTP ${response.code}")
        }
    }

    suspend fun bridgeSearch(path: String, query: String, searchType: String = "filename", maxResults: Int = 50, glob: String? = null): List<BridgeSearchResultDto> {
        val body = BridgeSearchRequest(path, query, searchType, maxResults, glob)
        val jsonStr = json.encodeToString(BridgeSearchRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/fs/search", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return emptyList()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        return json.decodeFromString(responseBody)
    }

    suspend fun bridgeStatus(): BridgeStatusResponse {
        return get("/api/bridge/status")
    }

    suspend fun bridgePairRequest(): PairRequestResponse {
        return post("/api/bridge/pair/request", JsonObject(emptyMap()))
    }

    suspend fun bridgePairConfirm(pin: String): PairConfirmResponse = withContext(Dispatchers.IO) {
        val body = PairConfirmRequest(pin)
        val jsonStr = json.encodeToString(PairConfirmRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/pair/confirm", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        json.decodeFromString(responseBody)
    }

    suspend fun bridgeScanPairConfirm(scanToken: String, deviceName: String, clientDeviceId: String): ScanPairConfirmResponse = withContext(Dispatchers.IO) {
        val body = ScanPairConfirmRequest(scanToken, deviceName, clientDeviceId)
        val jsonStr = json.encodeToString(ScanPairConfirmRequest.serializer(), body)
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl("/api/bridge/pair/scan-confirm", emptyMap())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        json.decodeFromString(responseBody)
    }

    suspend fun bridgeScanPairConfirmViaGateway(
        gatewayUrl: String,
        instanceId: String,
        scanToken: String,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse {
        val saved = baseUrl
        val savedIid = gatewayInterceptor?.instanceId
        baseUrl = gatewayUrl
        gatewayInterceptor?.instanceId = instanceId
        try {
            return bridgeScanPairConfirm(scanToken, deviceName, clientDeviceId)
        } finally {
            baseUrl = saved
            gatewayInterceptor?.instanceId = savedIid
        }
    }

    suspend fun isGatewayBridgeOnline(gatewayUrl: String, instanceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$gatewayUrl/api/gateway/status?instance_id=$instanceId")
                .get()
                .build()
            val response = OkHttpClient().newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun bridgeOpencodeVersion(): OpencodeVersionResponse {
        return get("/api/bridge/opencode/latest-version")
    }

    suspend fun bridgeOpencodeUpgrade(): OpencodeUpgradeResponse {
        return post("/api/bridge/opencode/upgrade", JsonObject(emptyMap()))
    }

    suspend fun bridgeOpencodeUpgradeStatus(): OpencodeUpgradeStatusResponse {
        return get("/api/bridge/opencode/upgrade-status")
    }

    suspend fun bridgeOpencodeRestart() {
        postUnit("/api/bridge/opencode/restart", JsonObject(emptyMap()))
    }

    suspend fun bridgeDownloadFile(
        path: String,
        destFile: java.io.File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) {
        val url = buildUrl("/api/bridge/fs/download", mapOf("path" to path))
        val request = Request.Builder().url(url).get().build()
        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
        val body = response.body ?: throw ServerUnavailableException("Empty response body")
        val contentLength = body.contentLength()
        destFile.parentFile?.mkdirs()
        body.byteStream().buffered().use { input ->
            destFile.outputStream().buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastProgressTime = System.currentTimeMillis()
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastProgressTime >= 1000) {
                        onProgress.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
                        lastProgressTime = now
                    }
                }
                output.flush()
                onProgress?.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
            }
        }
    }

    private suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T = withContext(Dispatchers.IO) {
        val request = requestBuilder("GET", path, params).get().build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            handleResponse(response)
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private suspend inline fun <reified T> getList(path: String, params: Map<String, String> = emptyMap()): List<T> = withContext(Dispatchers.IO) {
        val request = requestBuilder("GET", path, params).get().build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) {
                throw ServerUnavailableException("HTTP ${response.code}: $body")
            }
            json.decodeFromString(body)
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private suspend inline fun <reified T> post(path: String, body: Any, params: Map<String, String> = emptyMap()): T = withContext(Dispatchers.IO) {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            else -> json.encodeToString(JsonElement.serializer(), mapToJson(body as Map<*, *>))
        }
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val request = requestBuilder("POST", path, params)
            .post(requestBody)
            .build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            handleResponse(response)
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private suspend fun postUnit(path: String, body: Any, params: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            is Map<*, *> -> json.encodeToString(JsonElement.serializer(), mapToJson(body))
            else -> json.encodeToString(JsonElement.serializer(), JsonPrimitive(body.toString()))
        }
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val request = requestBuilder("POST", path, params)
            .post(requestBody)
            .build()
        runCatching { Log.d("OpencodeApiClient", "POST ${request.url} body=$jsonStr") }
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            if (!response.isSuccessful) {
                val respBody = response.body?.string()?.take(500) ?: ""
                runCatching { Log.e("OpencodeApiClient", "POST ${request.url} failed: HTTP ${response.code} body=$respBody") }
                throw ServerUnavailableException("HTTP ${response.code}: $respBody")
            }
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private fun mapToJson(map: Map<*, *>): JsonObject {
        return JsonObject(map.mapKeys { it.key.toString() }.mapValues { (_, v) ->
            when (v) {
                is JsonElement -> v
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> mapToJson(v)
                is List<*> -> JsonArray(v.map { item ->
                    when (item) {
                        is JsonElement -> item
                        is Map<*, *> -> mapToJson(item)
                        is List<*> -> listToJson(item)
                        is String -> JsonPrimitive(item)
                        else -> JsonPrimitive(item.toString())
                    }
                })
                else -> JsonPrimitive(v.toString())
            }
        })
    }

    private fun listToJson(list: List<*>): JsonArray {
        return JsonArray(list.map { item ->
            when (item) {
                is JsonElement -> item
                is Map<*, *> -> mapToJson(item)
                is List<*> -> listToJson(item)
                is String -> JsonPrimitive(item)
                else -> JsonPrimitive(item.toString())
            }
        })
    }

    private suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl$path").delete().build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NO_CONTENT) {
                throw ServerUnavailableException("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private suspend fun patch(path: String, body: Any) = withContext(Dispatchers.IO) {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            is Map<*, *> -> json.encodeToString(JsonElement.serializer(), mapToJson(body as Map<*, *>))
            else -> json.encodeToString(JsonElement.serializer(), JsonPrimitive(body.toString()))
        }
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl$path").patch(requestBody).build()
        val route = currentRoute()
        try {
            val response = client.newCall(request).execute()
            route?.let { routeEvidenceReporter?.reportSuccess(it) }
            if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NO_CONTENT) {
                throw ServerUnavailableException("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            route?.let { routeEvidenceReporter?.reportNetworkFailure(it, e.message) }
            throw e
        }
    }

    private fun currentRoute(): ConnectionRoute? {
        val gatewayId = gatewayInterceptor?.instanceId
        if (!gatewayId.isNullOrBlank()) {
            return ConnectionRoute.Gateway(gatewayId)
        }
        val url = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return null
        return ConnectionRoute.Direct(url.host, url.port)
    }

    private suspend inline fun <reified T> handleResponse(response: okhttp3.Response): T = withContext(Dispatchers.IO) {
        val body = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            when (response.code) {
                401 -> throw AuthException("Unauthorized: $body")
                404 -> throw NotFoundException("Not found: $body")
                else -> throw ServerUnavailableException("HTTP ${response.code}: $body")
            }
        }
        json.decodeFromString(body)
    }

    private fun requestBuilder(method: String, path: String, params: Map<String, String> = emptyMap()): Request.Builder {
        val isReadOnly = method == "GET" || method == "HEAD"
        val directory = params["directory"]
        val queryParams = if (isReadOnly) params else params - "directory"
        return Request.Builder()
            .url(buildUrl(path, queryParams))
            .apply {
                if (!isReadOnly && directory != null) {
                    header("x-opencode-directory", encodeDirectory(directory))
                }
            }
    }

    private fun encodeDirectory(directory: String): String {
        return URLEncoder.encode(directory, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private fun buildUrl(path: String, params: Map<String, String>): String {
        val builder = "$baseUrl$path".toHttpUrl().newBuilder()
        params.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    fun peek(request: Request): okhttp3.Response {
        return client.newCall(request).execute()
    }
}
