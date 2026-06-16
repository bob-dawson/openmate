package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpencodeApiClientGetMessageHeadersTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpencodeApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpencodeApiClient(
            client = OkHttpClient(),
            activeProfileProvider = object : ActiveProfileProvider {
                override fun getActiveProfile() = null
                override fun getActiveRoute() = com.openmate.core.domain.model.ConnectionRoute.Direct(
                    address = server.hostName,
                    port = server.port,
                )
            },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getMessageHeaders_returnsLightweightHeaders() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Next-Cursor", "cursor-1")
                .setBody(
                    """
                    [
                      {
                        "info": {
                          "id": "msg-1",
                          "sessionID": "ses-1",
                          "role": "assistant",
                          "time": {
                            "created": 1000,
                            "completed": null
                          }
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
        )

        val page = client.getMessageHeaders(sessionID = "ses-1", limit = 1, before = null)

        assertThat(page.nextCursor).isEqualTo("cursor-1")
        assertThat(page.items).hasSize(1)
        assertThat(page.items.single().info.id).isEqualTo("msg-1")
        assertThat(page.items.single().info.role).isEqualTo("assistant")
        assertThat(page.items.single().info.time.completed).isNull()
    }
}
