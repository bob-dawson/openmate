package com.openmate.app.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface NetworkChangeEvent {
    data object Available : NetworkChangeEvent
    data object Lost : NetworkChangeEvent
    data object PathChanged : NetworkChangeEvent
}

interface NetworkChangeMonitor {
    val events: Flow<NetworkChangeEvent>

    /** Whether a network with INTERNET capability is currently available. */
    fun hasInternet(): Boolean

    fun isWifiActive(): Boolean

    fun isCellularActive(): Boolean

    /**
     * Fingerprint of the current network environment, e.g. "wifi:wlan0:192.168.5.11/24".
     * Changes when the phone switches network or its local address changes.
     */
    fun networkFingerprint(): String?

    /** Local IPv4 addresses (ip/prefixLength) of the active network. */
    fun localIpv4Addresses(): List<LocalIpv4>
}

data class LocalIpv4(val ip: String, val prefixLength: Int)

@Singleton
class DefaultNetworkChangeMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkChangeMonitor {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun hasInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isWifiActive(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun isCellularActive(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    override fun networkFingerprint(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val caps = connectivityManager.getNetworkCapabilities(network)
        val link = connectivityManager.getLinkProperties(network) ?: return null
        val interfaceName = link.interfaceName ?: "?"
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "eth"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
            else -> "other"
        }
        val ipv4 = link.localIpv4()
        return if (ipv4 != null) {
            "$transport:$interfaceName:${ipv4.ip}/${ipv4.prefixLength}"
        } else {
            "$transport:$interfaceName"
        }
    }

    override fun localIpv4Addresses(): List<LocalIpv4> {
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val link = connectivityManager.getLinkProperties(network) ?: return emptyList()
        return link.linkAddresses.mapNotNull { linkAddress ->
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                LocalIpv4(address.hostAddress ?: return@mapNotNull null, linkAddress.prefixLength)
            } else {
                null
            }
        }
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val network = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getNetworkCapabilities(network)
    }

    private fun LinkProperties.localIpv4(): LocalIpv4? = linkAddresses
        .mapNotNull { linkAddress ->
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                LocalIpv4(address.hostAddress ?: return@mapNotNull null, linkAddress.prefixLength)
            } else {
                null
            }
        }
        .firstOrNull()

    override val events: Flow<NetworkChangeEvent> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkChangeEvent.Available)
            }

            override fun onLost(network: Network) {
                trySend(NetworkChangeEvent.Lost)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(NetworkChangeEvent.PathChanged)
            }
        }

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
