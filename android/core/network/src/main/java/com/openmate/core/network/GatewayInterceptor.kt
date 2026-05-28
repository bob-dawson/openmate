package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionRoute
import okhttp3.Interceptor
import okhttp3.Response

class GatewayInterceptor(
    private val activeProfileProvider: ActiveProfileProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = activeProfileProvider.getActiveRoute()
        val profile = activeProfileProvider.getActiveProfile()
        val useGateway = route is ConnectionRoute.Gateway
            || (route == null && profile?.instanceId?.isNotBlank() == true)
        val instanceId = when (route) {
            is ConnectionRoute.Gateway -> route.instanceId
            else -> profile?.instanceId?.ifBlank { null }
        }
        val request = if (useGateway && instanceId != null) {
            chain.request().newBuilder()
                .header("X-Instance-Id", instanceId)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
