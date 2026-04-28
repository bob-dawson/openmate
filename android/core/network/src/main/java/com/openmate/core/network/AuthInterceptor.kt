package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64

class AuthInterceptor(private val password: String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (password != null) {
            val credentials = ":" + password
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
            chain.request().newBuilder()
                .header("Authorization", "Basic $encoded")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
