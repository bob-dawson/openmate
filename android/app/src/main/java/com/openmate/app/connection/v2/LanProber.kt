package com.openmate.app.connection.v2

import com.openmate.core.network.dto.BridgeStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class LanProber(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun probeBest(
        expectedInstanceId: String,
        candidates: List<LanCandidate>,
    ): LanCandidate? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val results = candidates.map { candidate ->
            async(Dispatchers.IO) { candidate to probe(candidate, expectedInstanceId) }
        }.awaitAll()
        results.firstOrNull { it.second }?.first
    }

    private suspend fun probe(candidate: LanCandidate, expectedInstanceId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://${candidate.ip}:${candidate.port}/api/bridge/status")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body?.string() ?: return@withContext false
                    val status = runCatching {
                        json.decodeFromString<BridgeStatusResponse>(body)
                    }.getOrNull() ?: return@withContext false
                    if (expectedInstanceId.isNotBlank() &&
                        status.bridge.instanceId.isNotBlank() &&
                        status.bridge.instanceId != expectedInstanceId
                    ) {
                        return@withContext false
                    }
                    status.bridge.version.isNotBlank()
                }
            } catch (_: Exception) {
                false
            }
        }
}
