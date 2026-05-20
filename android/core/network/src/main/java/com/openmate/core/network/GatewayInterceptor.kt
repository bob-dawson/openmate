package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayInterceptor @Inject constructor() : Interceptor {
    @Volatile
    var instanceId: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val id = instanceId
        if (id == null) return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("X-Instance-Id", id)
            .build()
        return chain.proceed(request)
    }
}
