package com.openmate.feature.instance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.network.InstallationIdProvider
import com.openmate.core.network.OpencodeApiClient
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
    private val apiClient: OpencodeApiClient,
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
                val clientDeviceId = installationIdProvider.getInstallationId()
                val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                val response = performScanPair(parsed, deviceName, clientDeviceId)

                Log.d(TAG, "Scan pair confirmed, device_id=${response.deviceId}")
                _scanState.value = ScanResult(
                    name = parsed.name,
                    address = parsed.address,
                    port = parsed.port,
                    scanToken = parsed.scanToken,
                    token = response.token,
                    deviceId = response.deviceId,
                    instanceId = parsed.instanceId,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Scan pair failed: ${e.message}", e)
                processed = false
                _scanState.value = ScanUiStateError("Pairing failed: ${e.message}")
            }
        }
    }

    private suspend fun performScanPair(
        parsed: ParsedQrUrl,
        deviceName: String,
        clientDeviceId: String,
    ): ScanPairConfirmResponse {
        if (parsed.instanceId.isNotBlank()) {
            val online = apiClient.isGatewayBridgeOnline(GATEWAY_URL, parsed.instanceId)
            if (online) {
                Log.d(TAG, "Bridge online via gateway, pairing through gateway")
                return apiClient.bridgeScanPairConfirmViaGateway(
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
        val directUrl = "http://${parsed.address}:${parsed.port}"
        val saved = apiClient.baseUrl
        apiClient.baseUrl = directUrl
        try {
            return apiClient.bridgeScanPairConfirm(parsed.scanToken, deviceName, clientDeviceId)
        } finally {
            apiClient.baseUrl = saved
        }
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
        val instanceId: String,
    )

    private fun parseQrUrl(url: String): ParsedQrUrl? {
        if (url.startsWith("op:", ignoreCase = true)) {
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
                instanceId = instanceId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR URL: $url", e)
            null
        }
    }

    private fun parseCustomProtocol(url: String): ParsedQrUrl? {
        val body = url.removePrefix("op:")
        val parts = body.split(":", limit = 2)
        if (parts.size < 2) return null
        val iidB64 = parts[0]
        val st = parts[1]
        if (st.isBlank()) return null
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
            scanToken = st,
            instanceId = iid,
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
data class ScanResult(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val token: String,
    val deviceId: String,
    val instanceId: String,
) : ScanUiState