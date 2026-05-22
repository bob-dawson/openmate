package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncSseClientTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun connect_forceRestart_sameBaseUrl_startsFreshAttempt() = runTest {
        val callFactory = BlockingCallFactory()
        val client = SyncSseClient(
            client = callFactory,
            tokenStore = TokenStore(RuntimeEnvironment.getApplication()),
            logger = NoOpSyncSseLogger,
        )

        val first = launch(Dispatchers.IO) { client.connect("http://127.0.0.1:4097", forceRestart = true) }
        assertThat(callFactory.started.await(2, TimeUnit.SECONDS)).isTrue()

        val second = launch(Dispatchers.IO) { client.connect("http://127.0.0.1:4097", forceRestart = true) }
        advanceUntilIdle()

        client.disconnect("done")
        first.cancel()
        second.cancel()

        assertThat(callFactory.newCallCount).isAtLeast(2)
    }

    @Test
    fun connect_requestsBridgeEventsPath_forwardsHeaders_andEmitsBridgeEvent() = runBlocking {
        val tokenStore = TokenStore(RuntimeEnvironment.getApplication())
        tokenStore.saveToken(profileId = "profile-1", token = "test-token")
        tokenStore.setActiveProfileId("profile-1")

        val factory = StreamingCallFactory(
            sseBody = """
                data: {"type":"message.updated","properties":{"sessionID":"ses_123","directory":"/workspace","messageID":"msg_456","partID":"part_789"}}

            """.trimIndent()
        )
        val client = SyncSseClient(
            client = factory,
            tokenStore = tokenStore,
            logger = NoOpSyncSseLogger,
        )
        client.instanceId = "instance-123"

        val connectJob = async(Dispatchers.IO) {
            client.connect("http://127.0.0.1:4097")
        }
        val event = withTimeout(2_000) {
            client.notifications.first()
        }

        client.disconnect("done")
        withTimeout(2_000) {
            connectJob.await()
        }

        val request = factory.request ?: error("request was not captured")
        assertThat(request.url.toString()).isEqualTo("http://127.0.0.1:4097/api/bridge/events")
        assertThat(request.header("Authorization")).isEqualTo("Bearer test-token")
        assertThat(request.header("X-Instance-Id")).isEqualTo("instance-123")

        assertThat(event.type).isEqualTo("message.updated")
        assertThat(event.sessionId).isEqualTo("ses_123")
        assertThat(event.directory).isEqualTo("/workspace")
        assertThat(event.messageId).isEqualTo("msg_456")
        assertThat(event.partId).isEqualTo("part_789")
        assertThat(event.rawJson).isEqualTo(
            "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_123\",\"directory\":\"/workspace\",\"messageID\":\"msg_456\",\"partID\":\"part_789\"}}"
        )
    }

    @Test
    fun parseSessionUpdatedEvent_extractsMinimalBridgeFields() {
        val body = """{"type":"session.updated","properties":{"sessionID":"ses_123","directory":"/workspace","info":{"id":"ses_123","title":"Renamed"}}}"""

        val event: BridgeEvent? = BridgeEventParser.parse(body)

        assertThat(event?.type).isEqualTo("session.updated")
        assertThat(event?.sessionId).isEqualTo("ses_123")
        assertThat(event?.directory).isEqualTo("/workspace")
        assertThat(event?.rawJson).isEqualTo(body)
        assertThat(event?.properties?.get("info")?.jsonObject?.get("title")?.jsonPrimitive?.content)
            .isEqualTo("Renamed")
    }

    @Test
    fun parseSessionUpdatedEvent_preservesFullPayloadForDownstreamHandlers() {
        val body = """{"type":"session.updated","properties":{"sessionID":"ses_123","directory":"/workspace","info":{"id":"ses_123","title":"Renamed"},"status":{"type":"busy"},"error":{"message":"boom"},"permission":{"id":"perm_1"}}}"""

        val event: BridgeEvent? = BridgeEventParser.parse(body)

        assertThat(event?.rawJson).isEqualTo(body)
    }

    @Test
    fun parseMessageUpdatedEvent_extractsIdentifiers() {
        val body = """{"type":"message.updated","properties":{"sessionID":"ses_123","messageID":"msg_456","partID":"part_789"}}"""

        val event: BridgeEvent? = BridgeEventParser.parse(body)

        assertThat(event?.type).isEqualTo("message.updated")
        assertThat(event?.sessionId).isEqualTo("ses_123")
        assertThat(event?.messageId).isEqualTo("msg_456")
        assertThat(event?.partId).isEqualTo("part_789")
        assertThat(event?.properties?.get("messageID")?.jsonPrimitive?.content).isEqualTo("msg_456")
    }

    @Test
    fun parseDropsMalformedOrNonPrimitiveFields_withoutThrowing() {
        assertThat(BridgeEventParser.parse("not json")).isNull()
        assertThat(BridgeEventParser.parse("[]")).isNull()
        assertThat(BridgeEventParser.parse("{\"type\":{\"nested\":true},\"properties\":{\"sessionID\":\"ses_123\"}}"))
            .isNull()
        assertThat(BridgeEventParser.parse("{\"type\":\"message.updated\",\"properties\":[]}"))
            .isNull()
        assertThat(BridgeEventParser.parse("{\"type\":\"message.updated\",\"properties\":{\"sessionID\":{\"bad\":true}}}"))
            .isNull()
    }

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

    @Test
    fun parsedSseEvent_emitsStrongPositiveSignal() = runBlocking {
        val callFactory = StreamingCallFactory(
            sseBody = "data: {\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"ses_1\"}}\n\n"
        )
        val client = SyncSseClient(
            client = callFactory,
            tokenStore = TokenStore(RuntimeEnvironment.getApplication()),
            logger = NoOpSyncSseLogger,
        )

        val signalDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            client.transportSignals.first { it is SyncSseSignal.EventReceived }
        }
        val connectJob = launch(Dispatchers.IO) { client.connect("http://127.0.0.1:4097", forceRestart = true) }
        val signal = withTimeout(2_000) { signalDeferred.await() }

        client.disconnect("done")
        connectJob.cancelAndJoin()

        assertThat((signal as SyncSseSignal.EventReceived).routeBaseUrl).isEqualTo("http://127.0.0.1:4097")
    }

    @Test
    fun streamFailure_emitsSuspicionSignal() = runBlocking {
        val client = SyncSseClient(
            client = AlwaysFailingCallFactory(),
            tokenStore = TokenStore(RuntimeEnvironment.getApplication()),
            logger = NoOpSyncSseLogger,
        )

        val signalDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            client.transportSignals.first { it is SyncSseSignal.Failed }
        }
        val connectJob = launch(Dispatchers.IO) { client.connect("http://127.0.0.1:4097", forceRestart = true) }
        val signal = withTimeout(2_000) { signalDeferred.await() }

        client.disconnect("done")
        connectJob.cancelAndJoin()

        assertThat(signal).isInstanceOf(SyncSseSignal.Failed::class.java)
    }

    private class StreamingCallFactory(
        private val sseBody: String,
    ) : Call.Factory {
        @Volatile
        var request: Request? = null

        override fun newCall(request: Request): Call {
            this.request = request
            return StreamingCall(request, sseBody)
        }
    }

    private class StreamingCall(
        private val requestValue: Request,
        private val sseBody: String,
    ) : Call {
        @Volatile
        private var canceled = false

        override fun request(): Request = requestValue

        override fun execute(): Response {
            return Response.Builder()
                .request(requestValue)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = StreamingCall(requestValue, sseBody)
    }

    private class BlockingCallFactory : Call.Factory {
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        var newCallCount = 0

        override fun newCall(request: Request): Call {
            newCallCount += 1
            return BlockingCall(request, started, canceled)
        }
    }

    private class AlwaysFailingCallFactory : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
            override fun request(): Request = request

            override fun execute(): Response {
                throw IOException("boom")
            }

            override fun enqueue(responseCallback: Callback) = Unit

            override fun cancel() = Unit

            override fun isExecuted(): Boolean = true

            override fun isCanceled(): Boolean = false

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = this
        }
    }

    private class BlockingCall(
        private val requestValue: Request,
        private val started: CountDownLatch,
        private val canceled: CountDownLatch,
    ) : Call {
        @Volatile
        private var isCanceled = false

        override fun request(): Request = requestValue

        override fun execute(): Response {
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
