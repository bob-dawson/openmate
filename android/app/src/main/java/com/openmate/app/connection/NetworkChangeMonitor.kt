package com.openmate.app.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
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
}

@Singleton
class DefaultNetworkChangeMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkChangeMonitor {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
