package com.openmate.feature.instance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URL
import javax.inject.Inject

private const val TAG = "QrScanViewModel"

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
    private val profileRepository: ServerProfileRepository,
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
                val baseUrl = "http://${parsed.address}:${parsed.port}"
                val saved = apiClient.baseUrl
                apiClient.baseUrl = baseUrl
                val response = try {
                    apiClient.bridgeScanPairConfirm(parsed.scanToken, parsed.name)
                } finally {
                    apiClient.baseUrl = saved
                }

                Log.d(TAG, "Scan pair confirmed, device_id=${response.deviceId}")
                _scanState.value = ScanResult(
                    name = parsed.name,
                    address = parsed.address,
                    port = parsed.port,
                    scanToken = parsed.scanToken,
                    token = response.token,
                    deviceId = response.deviceId,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Scan pair failed: ${e.message}", e)
                processed = false
                _scanState.value = ScanUiStateError("Pairing failed: ${e.message}")
            }
        }
    }

    fun setError(message: String) {
        _scanState.value = ScanUiStateError(message)
    }

    fun handleScanComplete(name: String, address: String, port: Int) {
        val currentState = _scanState.value
        if (currentState !is ScanResult) return

        viewModelScope.launch(Dispatchers.IO) {
            val profileId = java.util.UUID.randomUUID().toString()
            tokenStore.saveToken(profileId, currentState.token)
            profileRepository.save(
                ServerProfile(
                    id = profileId,
                    name = name,
                    address = address,
                    port = port,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    data class ParsedQrUrl(
        val name: String,
        val address: String,
        val port: Int,
        val scanToken: String,
    )

    private fun parseQrUrl(url: String): ParsedQrUrl? {
        return try {
            val parsed = URL(url)
            val port = parsed.port.takeIf { it > 0 } ?: 4097
            val query = parsed.query ?: ""
            val params = parseQueryParams(query)

            val name = params["name"] ?: "Bridge"
            val scanToken = params["st"] ?: return null

            ParsedQrUrl(
                name = java.net.URLDecoder.decode(name, "UTF-8"),
                address = parsed.host,
                port = port,
                scanToken = scanToken,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR URL: $url", e)
            null
        }
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
) : ScanUiState
