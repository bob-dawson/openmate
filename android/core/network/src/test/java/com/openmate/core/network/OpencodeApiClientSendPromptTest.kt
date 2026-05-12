package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpencodeApiClientSendPromptTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpencodeApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpencodeApiClient(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendPrompt_includesVariantWhenProvided() {
        server.enqueue(MockResponse().setResponseCode(204))

        kotlinx.coroutines.runBlocking {
            client.sendPrompt(
                sessionID = "ses_123",
                content = "hello",
                providerID = "openai",
                modelID = "gpt-5",
                variant = "high",
            )
        }

        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertThat(request.path).isEqualTo("/session/ses_123/prompt_async")
        assertThat(body).contains("\"variant\":\"high\"")
    }
}
