package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.ConnectionStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class SseClientTest {
    @Test
    fun staleFirstConnectionFailure_doesNotOverrideSecondConnectedState() {
        val firstCallStarted = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val allowFirstFailure = CountDownLatch(1)
        val callCount = AtomicInteger(0)

        val client = SseClient(
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        when (callCount.incrementAndGet()) {
                            1 -> {
                                firstCallStarted.countDown()
                                check(allowFirstFailure.await(2, TimeUnit.SECONDS))
                                Response.Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(500)
                                    .message("stale failed")
                                    .body("stale failed".toResponseBody("text/plain".toMediaType()))
                                    .build()
                            }
                            2 -> {
                                secondCallStarted.countDown()
                                Response.Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body("".toResponseBody("text/event-stream".toMediaType()))
                                    .build()
                            }
                            else -> {
                                Response.Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(500)
                                    .message("unexpected")
                                    .body("unexpected".toResponseBody("text/plain".toMediaType()))
                                    .build()
                            }
                        }
                    }
                )
                .build()
        )

        client.connectViaGateway("http://example.test")
        check(firstCallStarted.await(2, TimeUnit.SECONDS))

        client.connectViaGateway("http://example.test")
        check(secondCallStarted.await(2, TimeUnit.SECONDS))
        waitUntil("second connection should reach CONNECTED") {
            client.connectionStatus.value == ConnectionStatus.CONNECTED
        }

        allowFirstFailure.countDown()
        Thread.sleep(100)
        assertThat(client.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTED)
    }

    private fun waitUntil(message: String, timeoutMs: Long = 2_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        check(predicate()) { message }
    }
}
