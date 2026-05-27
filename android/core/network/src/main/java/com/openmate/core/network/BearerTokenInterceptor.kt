package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hasAuth = request.header("Authorization") != null
        val token = tokenStore.activeToken
        val newRequest = if (!hasAuth && token != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        return chain.proceed(newRequest)
    }
}
