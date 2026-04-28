package com.openmate.core.network

import com.openmate.core.network.dto.HealthDto
import com.openmate.core.network.dto.MessageWithPartsDto
import com.openmate.core.network.dto.PermissionDto
import com.openmate.core.network.dto.QuestionDto
import com.openmate.core.network.dto.SessionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

class OpencodeApiClient(
    private val client: OkHttpClient,
    var baseUrl: String = "http://localhost:8080",
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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

    suspend fun createSession(title: String? = null): SessionDto {
        val body = mutableMapOf<String, String>()
        title?.let { body["title"] = it }
        return post("/session", body)
    }

    suspend fun deleteSession(id: String) {
        delete("/session/$id")
    }

    suspend fun getMessages(sessionID: String, limit: Int, before: String?): List<MessageWithPartsDto> {
        val params = mutableMapOf<String, String>()
        params["limit"] = limit.toString()
        before?.let { params["before"] = it }
        return getList("/session/$sessionID/message", params)
    }

    fun sendMessageStream(sessionID: String, content: String): Flow<SseData> {
        val body = mapOf("text" to content)
        val requestBody = json.encodeToString(body).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/session/$sessionID/prompt")
            .post(requestBody)
            .build()

        return kotlinx.coroutines.flow.flow {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw ServerUnavailableException("HTTP ${response.code}")
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            reader.useLines { lines ->
                for (line in lines) {
                    val sseData = SseParser.parseLine(line)
                    if (sseData != null) {
                        emit(sseData)
                    }
                }
            }
        }
    }

    suspend fun sendPromptAsync(sessionID: String, content: String) {
        val body = mapOf("content" to content)
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

    suspend fun healthCheck(): HealthDto {
        return get("/global/health")
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

    private inline fun <reified T> post(path: String, body: Any): T {
        val requestBody = json.encodeToString(body).toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl$path").post(requestBody).build()
        val response = client.newCall(request).execute()
        return handleResponse(response)
    }

    private fun postUnit(path: String, body: Any) {
        val requestBody = json.encodeToString(body).toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl$path").post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
    }

    private fun delete(path: String) {
        val request = Request.Builder().url("$baseUrl$path").delete().build()
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
