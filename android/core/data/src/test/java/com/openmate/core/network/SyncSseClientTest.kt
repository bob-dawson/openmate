package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okio.Timeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncSseClientTest {

    @Test
    fun disconnect_cancelsActiveBlockingCall() = runBlocking {
        val factory = BlockingCallFactory()
        val client = SyncSseClient(
            client = factory,
            tokenStore = TokenStore(RuntimeEnvironment.getApplication()),
            logger = NoOpSyncSseLogger,
        )

        val connectJob = async(Dispatchers.IO) {
            client.connect("http://127.0.0.1:4097")
        }

        assertThat(factory.started.await(2, TimeUnit.SECONDS)).isTrue()

        client.disconnect("manual")

        withTimeout(2_000) {
            connectJob.await()
        }

        assertThat(factory.canceled.await(2, TimeUnit.SECONDS)).isTrue()
    }

    private class BlockingCallFactory : Call.Factory {
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)

        override fun newCall(request: Request): Call = BlockingCall(request, started, canceled)
    }

    private class BlockingCall(
        private val requestValue: Request,
        private val started: CountDownLatch,
        private val canceled: CountDownLatch,
    ) : Call {
        @Volatile
        private var isCanceled = false

        override fun request(): Request = requestValue

        override fun execute(): okhttp3.Response {
            started.countDown()
            while (!isCanceled) {
                Thread.sleep(10)
            }
            canceled.countDown()
            throw IOException("canceled")
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() {
            isCanceled = true
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = isCanceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = BlockingCall(requestValue, started, canceled)
    }

    private object NoOpSyncSseLogger : SyncSseLogger {
        override fun logConnectStart(traceId: String, hasToken: Boolean) = Unit
        override fun logConnectSuccess(traceId: String, costMs: Long) = Unit
        override fun logDisconnected(traceId: String?, currentBaseUrl: String?) = Unit
        override fun logConnectFailure(traceId: String, error: Throwable) = Unit
        override fun logStreamClosed(traceId: String) = Unit
        override fun logNotification(event: BridgeEvent, traceId: String) = Unit
    }
}
