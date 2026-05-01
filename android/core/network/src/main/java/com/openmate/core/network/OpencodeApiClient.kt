package com.openmate.core.network

import com.openmate.core.network.dto.HealthDto
import com.openmate.core.network.dto.MessageWithPartsDto
import com.openmate.core.network.dto.PermissionDto
import com.openmate.core.network.dto.ProviderListDto
import com.openmate.core.network.dto.QuestionDto
import com.openmate.core.network.dto.SessionDto
import com.openmate.core.network.dto.PathInfo
import com.openmate.core.network.dto.SessionStatusDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection

class OpencodeApiClient(
    private val client: OkHttpClient,
    var baseUrl: String = "http://localhost:8080",
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class MessagesPage(
        val items: List<MessageWithPartsDto>,
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

    suspend fun getMessages(sessionID: String, limit: Int, before: String?): MessagesPage {
        val params = mutableMapOf<String, String>()
        params["limit"] = limit.toString()
        before?.let { params["before"] = it }
        val url = buildUrl("/session/$sessionID/message", params)
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return MessagesPage(emptyList(), null)
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        val items: List<MessageWithPartsDto> = json.decodeFromString(body)
        val nextCursor = response.header("X-Next-Cursor")
        return MessagesPage(items, nextCursor)
    }

    suspend fun sendPrompt(sessionID: String, content: String, providerID: String? = null, modelID: String? = null) {
        val parts = mapOf(
            "parts" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to content
                )
            )
        )
        val extraFields = mutableMapOf<String, JsonElement>()
        if (providerID != null && modelID != null) {
            extraFields["model"] = JsonObject(mapOf(
                "providerID" to JsonPrimitive(providerID),
                "modelID" to JsonPrimitive(modelID),
            ))
        }
        val bodyMap = parts + extraFields
        val body = mapToJson(bodyMap)
        android.util.Log.d("OpencodeApiClient", "sendPrompt url=$baseUrl/session/$sessionID/prompt_async body=$body")
        postUnit("/session/$sessionID/prompt_async", body)
    }

    suspend fun abortSession(sessionID: String) {
        postUnit("/session/$sessionID/abort", emptyMap<String, String>())
    }

    suspend fun listPermissions(): List<PermissionDto> {
        return getList("/permission")
    }

    suspend fun replyPermission(requestID: String, reply: String, message: String?) {
        val body = mutableMapOf<String, String>()
        body["reply"] = reply
        message?.let { body["message"] = it }
        postUnit("/permission/$requestID/reply", body)
    }

    suspend fun listQuestions(): List<QuestionDto> {
        return getList("/question")
    }

    suspend fun replyQuestion(requestID: String, answers: List<List<String>>) {
        val body = mapOf("answers" to answers)
        postUnit("/question/$requestID/reply", body)
    }

    suspend fun rejectQuestion(requestID: String) {
        postUnit("/question/$requestID/reject", emptyMap<String, String>())
    }

    suspend fun getSessionStatuses(): Map<String, SessionStatusDto> {
        return get("/session/status")
    }

    suspend fun getTodos(sessionID: String): List<com.openmate.core.domain.model.TodoInfo> {
        val url = buildUrl("/session/$sessionID/todo", emptyMap())
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

    suspend fun getProviders(): ProviderListDto {
        return get("/provider")
    }

    suspend fun summarizeSession(sessionID: String, providerID: String, modelID: String) {
        val body = mapOf(
            "providerID" to providerID,
            "modelID" to modelID,
        )
        postUnit("/session/$sessionID/summarize", body)
    }

    private inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T {
        val url = buildUrl(path, params)
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        return handleResponse(response)
    }

    private inline fun <reified T> getList(path: String, params: Map<String, String> = emptyMap()): List<T> {
        val url = buildUrl(path, params)
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        return json.decodeFromString(body)
    }

    private inline fun <reified T> post(path: String, body: Any, params: Map<String, String> = emptyMap()): T {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            else -> json.encodeToString(JsonElement.serializer(), mapToJson(body as Map<*, *>))
        }
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val url = buildUrl(path, params)
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        return handleResponse(response)
    }

    private fun postUnit(path: String, body: Any) {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            is Map<*, *> -> json.encodeToString(JsonElement.serializer(), mapToJson(body))
            else -> json.encodeToString(JsonElement.serializer(), JsonPrimitive(body.toString()))
        }
        val url = "$baseUrl$path"
        android.util.Log.d("OpencodeApiClient", "postUnit START url=$url json=$jsonStr")
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(requestBody).build()
        android.util.Log.d("OpencodeApiClient", "postUnit EXECUTING...")
        val response = client.newCall(request).execute()
        android.util.Log.d("OpencodeApiClient", "postUnit GOT response: ${response.code} body=${response.body?.string()?.take(200)}")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    private fun mapToJson(map: Map<*, *>): JsonObject {
        return JsonObject(map.mapKeys { it.key.toString() }.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> mapToJson(v)
                is List<*> -> JsonArray(v.map { item ->
                    when (item) {
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
                is Map<*, *> -> mapToJson(item)
                is List<*> -> listToJson(item)
                is String -> JsonPrimitive(item)
                else -> JsonPrimitive(item.toString())
            }
        })
    }

    private fun delete(path: String) {
        val request = Request.Builder().url("$baseUrl$path").delete().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NO_CONTENT) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    private fun patch(path: String, body: Any) {
        val jsonStr = when (body) {
            is JsonElement -> json.encodeToString(JsonElement.serializer(), body)
            is Map<*, *> -> json.encodeToString(JsonElement.serializer(), mapToJson(body as Map<*, *>))
            else -> json.encodeToString(JsonElement.serializer(), JsonPrimitive(body.toString()))
        }
        val requestBody = jsonStr.toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl$path").patch(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NO_CONTENT) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    private inline fun <reified T> handleResponse(response: okhttp3.Response): T {
        val body = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            when (response.code) {
                401 -> throw AuthException("Unauthorized: $body")
                404 -> throw NotFoundException("Not found: $body")
                else -> throw ServerUnavailableException("HTTP ${response.code}: $body")
            }
        }
        return json.decodeFromString(body)
    }

    private fun buildUrl(path: String, params: Map<String, String>): String {
        val builder = StringBuilder("$baseUrl$path")
        if (params.isNotEmpty()) {
            builder.append("?")
            params.entries.joinTo(builder, "&") { "${it.key}=${it.value}" }
        }
        return builder.toString()
    }
}
