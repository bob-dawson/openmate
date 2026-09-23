package com.openmate.core.data

import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.network.ActiveProfileProvider
import com.openmate.core.network.OpencodeApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

/**
 * Builds an [OpencodeApiClient] whose base URL resolves to the given direct route.
 * OpencodeApiClient derives its baseUrl from an ActiveProfileProvider, so tests
 * must supply one instead of a baseUrl constructor argument.
 */
fun mockOpencodeApiClient(address: String = "127.0.0.1", port: Int = 4097): OpencodeApiClient {
    val profile = ServerProfile(
        id = "test-profile",
        name = "test",
        address = address,
        port = port,
        createdAt = 0L,
        gatewayEnabled = false,
    )
    val provider = object : ActiveProfileProvider {
        override fun getActiveProfile(): ServerProfile = profile
        override fun getActiveRoute(): ConnectionRoute = ConnectionRoute.Direct(address, port)
    }
    return OpencodeApiClient(OkHttpClient(), activeProfileProvider = provider)
}

fun mockOpencodeApiClient(server: MockWebServer): OpencodeApiClient =
    mockOpencodeApiClient(address = "127.0.0.1", port = server.port)
