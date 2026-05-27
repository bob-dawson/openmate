package com.openmate.core.network

import com.openmate.core.network.dto.BridgeStatusResponse
import com.openmate.core.network.dto.PairConfirmResponse
import com.openmate.core.network.dto.PairRequestResponse
import com.openmate.core.network.dto.ScanPairConfirmResponse
import com.openmate.core.network.dto.PairConfirmRequest
import com.openmate.core.network.dto.ScanPairConfirmRequest
import com.openmate.core.network.dto.LoginConfirmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object TempHttpClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gatewayClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchBridgeStatus(baseUrl: String): BridgeStatusResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/bridge/status")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        json.decodeFromString(body)
    }

    suspend fun pairRequest(baseUrl: String): PairRequestResponse = withContext(Dispatchers.IO) {
        val requestBody = JsonObject(emptyMap()).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/bridge/pair/request")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $body")
        }
        json.decodeFromString(body)
    }

    suspend fun pairConfirm(baseUrl: String, pin: String): PairConfirmResponse = withContext(Dispatchers.IO) {
        val body = PairConfirmRequest(pin)
        val jsonStr = json.encodeToString(PairConfirmRequest.serializer(), body)
        val request = Request.Builder()
            .url("$baseUrl/api/bridge/pair/confirm")
            .post(jsonStr.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        json.decodeFromString(responseBody)
    }

    suspend fun scanPairConfirm(
        baseUrl: String,
        scanToken: String,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse = withContext(Dispatchers.IO) {
        val body = ScanPairConfirmRequest(scanToken, deviceName, clientDeviceId)
        val jsonStr = json.encodeToString(ScanPairConfirmRequest.serializer(), body)
        val request = Request.Builder()
            .url("$baseUrl/api/bridge/pair/scan-confirm")
            .post(jsonStr.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        json.decodeFromString(responseBody)
    }

    suspend fun scanPairConfirmViaGateway(
        gatewayUrl: String,
        instanceId: String,
        scanToken: String,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse = withContext(Dispatchers.IO) {
        val body = ScanPairConfirmRequest(scanToken, deviceName, clientDeviceId)
        val jsonStr = json.encodeToString(ScanPairConfirmRequest.serializer(), body)
        val request = Request.Builder()
            .url("$gatewayUrl/api/bridge/pair/scan-confirm")
            .header("X-Instance-Id", instanceId)
            .post(jsonStr.toRequestBody(jsonMediaType))
            .build()
        val response = gatewayClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
        json.decodeFromString(responseBody)
    }

    suspend fun loginConfirm(
        baseUrl: String,
        authToken: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val body = LoginConfirmRequest(sessionId)
        val jsonStr = json.encodeToString(LoginConfirmRequest.serializer(), body)
        val request = Request.Builder()
            .url("$baseUrl/api/bridge/login/confirm")
            .header("Authorization", "Bearer $authToken")
            .post(jsonStr.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
    }

    suspend fun loginConfirmViaGateway(
        gatewayUrl: String,
        instanceId: String,
        authToken: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val body = LoginConfirmRequest(sessionId)
        val jsonStr = json.encodeToString(LoginConfirmRequest.serializer(), body)
        val request = Request.Builder()
            .url("$gatewayUrl/api/bridge/login/confirm")
            .header("Authorization", "Bearer $authToken")
            .header("X-Instance-Id", instanceId)
            .post(jsonStr.toRequestBody(jsonMediaType))
            .build()
        val response = gatewayClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
        }
    }

    suspend fun isGatewayBridgeOnline(gatewayUrl: String, instanceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$gatewayUrl/api/gateway/status?instance_id=$instanceId")
                .get()
                .build()
            val response = gatewayClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
