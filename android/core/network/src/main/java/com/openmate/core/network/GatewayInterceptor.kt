package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response

class GatewayInterceptor(
    private val activeProfileProvider: ActiveProfileProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val id = activeProfileProvider.getActiveProfile()?.instanceId?.ifBlank { null }
        if (id == null) return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("X-Instance-Id", id)
            .build()
        return chain.proceed(request)
    }
}
