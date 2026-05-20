package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpencodeApiClientDirectoryRoutingTest {

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
    fun createSession_withDirectory_usesEncodedDirectoryHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {"id":"ses_123","title":"t","directory":"D:/openmate test"}
                """.trimIndent()),
        )

        client.createSession(
            title = "t",
            directory = "D:\\openmate test",
        )

        val request = server.takeRequest()

        assertThat(request.path).isEqualTo("/session")
        assertThat(request.getHeader("x-opencode-directory")).isEqualTo("D%3A%5Copenmate%20test")
    }

    @Test
    fun getMessageHeaders_withDirectory_usesEncodedDirectoryQuery() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[]"),
        )

        client.getMessageHeaders(
            sessionID = "ses-1",
            limit = 1,
            before = null,
            directory = "D:\\openmate test",
        )

        val request = server.takeRequest()

        assertThat(request.path).isEqualTo("/session/ses-1/message?limit=1&directory=D%3A%5Copenmate%20test")
        assertThat(request.getHeader("x-opencode-directory")).isNull()
    }
}
