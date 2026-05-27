package com.openmate.feature.instance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.network.InstallationIdProvider
import com.openmate.core.network.TempHttpClient
import com.openmate.core.network.TokenStore
import com.openmate.core.network.dto.ScanPairConfirmResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URL
import java.util.Base64
import javax.inject.Inject

private const val TAG = "QrScanViewModel"
private const val GATEWAY_URL = "https://gateway.clawmate.net"

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val profileRepository: ServerProfileRepository,
    private val installationIdProvider: InstallationIdProvider,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiIdle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private var processed = false

    fun handleBarcode(url: String) {
        if (processed) return

        val parsed = parseQrUrl(url)
        if (parsed == null) {
            _scanState.value = ScanUiStateError("Invalid QR code: not a Bridge URL")
            return
        }

        processed = true
        _scanState.value = ScanUiStateProcessing

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (parsed.isLogin) {
                    performLogin(parsed)
                    _scanState.value = ScanUiLoginConfirmed
                } else {
                    val clientDeviceId = installationIdProvider.getInstallationId()
                    val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    val response = performScanPair(parsed, deviceName, clientDeviceId)

                    Log.d(TAG, "Scan pair confirmed, device_id=${response.deviceId}")
                    _scanState.value = ScanResult(
                        name = parsed.name.ifEmpty { response.name },
                        address = parsed.address.ifEmpty { response.address },
                        port = if (parsed.port != 0) parsed.port else response.port,
                        scanToken = parsed.scanToken,
                        token = response.token,
                        deviceId = response.deviceId,
                        instanceId = parsed.instanceId,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Flow failed: ${e.message}", e)
                processed = false
                _scanState.value = ScanUiStateError(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun performScanPair(
        parsed: ParsedQrUrl,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse {
        if (parsed.instanceId.isNotBlank()) {
            val online = TempHttpClient.isGatewayBridgeOnline(GATEWAY_URL, parsed.instanceId)
            if (online) {
                Log.d(TAG, "Bridge online via gateway, pairing through gateway")
                return TempHttpClient.scanPairConfirmViaGateway(
                    GATEWAY_URL, parsed.instanceId, parsed.scanToken, deviceName, clientDeviceId,
                )
            }
            Log.d(TAG, "Bridge not online via gateway, falling back to LAN")
        }

        if (parsed.address.isBlank()) {
            throw Exception("No LAN address available and Bridge is not online via gateway")
        }

        return pairViaLan(parsed, deviceName, clientDeviceId)
    }

    private suspend fun pairViaLan(
        parsed: ParsedQrUrl,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse {
        val baseUrl = "http://${parsed.address}:${parsed.port}"
        return TempHttpClient.scanPairConfirm(baseUrl, parsed.scanToken, deviceName, clientDeviceId)
    }

    private suspend fun performLogin(parsed: ParsedQrUrl) {
        val profile = profileRepository.getAll().firstOrNull { it.instanceId == parsed.instanceId }
            ?: throw Exception("此 Bridge 尚未配对，请先在 Bridge 管理页面完成设备配对")

        val token = tokenStore.getToken(profile.id)
            ?: throw Exception("未找到设备凭证，请重新配对")

        if (parsed.instanceId.isNotBlank()) {
            val online = TempHttpClient.isGatewayBridgeOnline(GATEWAY_URL, parsed.instanceId)
            if (online) {
                Log.d(TAG, "Bridge online via gateway, confirming login through gateway")
                TempHttpClient.loginConfirmViaGateway(
                    GATEWAY_URL, parsed.instanceId, token, parsed.sessionId,
                )
                Log.d(TAG, "Login confirmed via gateway")
                return
            }
            Log.d(TAG, "Bridge not online via gateway, falling back to LAN")
        }

        if (profile.address.isBlank()) {
            throw Exception("Bridge 地址未知且不在线")
        }
        loginViaLan(profile, token, parsed.sessionId)
    }

    private suspend fun loginViaLan(profile: ServerProfile, authToken: String, sessionId: String) {
        val baseUrl = "http://${profile.address}:${profile.port}"
        TempHttpClient.loginConfirm(baseUrl, authToken, sessionId)
        Log.d(TAG, "Login confirmed via LAN for ${profile.address}:${profile.port}")
    }

    fun setError(message: String) {
        _scanState.value = ScanUiStateError(message)
    }

    fun reset() {
        processed = false
        _scanState.value = ScanUiIdle
    }

    fun handleScanComplete(name: String, address: String, port: Int) {
        val currentState = _scanState.value
        if (currentState !is ScanResult) return

        viewModelScope.launch(Dispatchers.IO) {
            val existingProfile = if (currentState.instanceId.isNotEmpty()) {
                profileRepository.getAll().find { it.instanceId == currentState.instanceId }
            } else {
                null
            }

            val saved: ServerProfile
            if (existingProfile != null) {
                saved = existingProfile.copy(
                    name = name,
                    address = address,
                    port = port,
                )
                tokenStore.saveToken(existingProfile.id, currentState.token)
                profileRepository.save(saved)
            } else {
                val profileId = java.util.UUID.randomUUID().toString()
                saved = ServerProfile(
                    id = profileId,
                    name = name,
                    address = address,
                    port = port,
                    instanceId = currentState.instanceId,
                    createdAt = System.currentTimeMillis(),
                )
                tokenStore.saveToken(profileId, currentState.token)
                profileRepository.save(saved)
            }
            connectionRepository.notifyProfileUpdated(saved)
        }
    }

    data class ParsedQrUrl(
        val name: String,
        val address: String,
        val port: Int,
        val scanToken: String,
        val sessionId: String,
        val instanceId: String,
        val isLogin: Boolean = false,
    )

    private fun parseQrUrl(url: String): ParsedQrUrl? {
        if (url.startsWith("op:", ignoreCase = true) || url.startsWith("oplogin:", ignoreCase = true)) {
            return parseCustomProtocol(url)
        }
        return try {
            val parsed = URL(url)
            val port = parsed.port.takeIf { it > 0 } ?: 4097
            val query = parsed.query ?: ""
            val params = parseQueryParams(query)

            val name = params["name"] ?: "Bridge"
            val scanToken = params["st"] ?: return null
            val instanceId = params["iid"] ?: ""

            ParsedQrUrl(
                name = java.net.URLDecoder.decode(name, "UTF-8"),
                address = parsed.host,
                port = port,
                scanToken = scanToken,
                sessionId = "",
                instanceId = instanceId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR URL: $url", e)
            null
        }
    }

    private fun parseCustomProtocol(url: String): ParsedQrUrl? {
        val isLogin = url.startsWith("oplogin:", ignoreCase = true)
        val body = if (isLogin) url.removePrefix("oplogin:") else url.removePrefix("op:")
        val parts = body.split(":", limit = 2)
        if (parts.size < 2) return null
        val iidB64 = parts[0]
        val token = parts[1]
        if (token.isBlank()) return null
        val iid = try {
            val bytes = Base64.getUrlDecoder().decode(iidB64)
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode iid base64url: ${e.message}")
            iidB64
        }
        return ParsedQrUrl(
            name = "",
            address = "",
            port = 0,
            scanToken = if (isLogin) "" else token,
            sessionId = if (isLogin) token else "",
            instanceId = iid,
            isLogin = isLogin,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                result[parts[0]] = parts[1]
            }
        }
        return result
    }
}

sealed interface ScanUiState

data object ScanUiIdle : ScanUiState
data object ScanUiStateProcessing : ScanUiState
data class ScanUiStateError(val message: String) : ScanUiState
data object ScanUiLoginConfirmed : ScanUiState
data class ScanResult(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val token: String,
    val deviceId: String,
    val instanceId: String,
) : ScanUiState