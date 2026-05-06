package com.openmate.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.activeToken
        val request = if (token != null) {
            Log.d("BearerToken", "Adding Bearer token (${token.length} chars) to ${chain.request().url}")
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            Log.w("BearerToken", "NO active token for ${chain.request().url}")
            chain.request()
        }
        return chain.proceed(request)
    }
}
