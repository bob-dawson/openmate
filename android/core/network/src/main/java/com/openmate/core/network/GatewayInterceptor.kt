package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionRoute
import okhttp3.Interceptor
import okhttp3.Response

class GatewayInterceptor(
    private val activeProfileProvider: ActiveProfileProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = activeProfileProvider.getActiveRoute()
        val request = if (route is ConnectionRoute.Gateway) {
            chain.request().newBuilder()
                .header("X-Instance-Id", route.instanceId)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
