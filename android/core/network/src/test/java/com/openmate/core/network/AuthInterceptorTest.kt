package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun noPassword_doesNotAddHeader() {
        val interceptor = AuthInterceptor(null)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun withPassword_addsBasicAuthHeader() {
        val interceptor = AuthInterceptor("secret123")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        val authHeader = recorded.getHeader("Authorization")
        assertThat(authHeader).isNotNull()
        assertThat(authHeader).startsWith("Basic ")
    }
}
