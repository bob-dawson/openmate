package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.openmate.core.common.AppDispatchers
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncDebugControllerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reconnect_logsManualAndReconnectsSyncSse() = runTest {
        val logStore = SyncLogStore()
        val connection = FakeSyncSseConnection(
            currentBaseUrl = "http://127.0.0.1:4097",
            logStore = logStore,
        )
        val starter = FakeSyncSseStarter()
        val controller = SyncDebugController(
            syncSseConnection = connection,
            syncSseStarter = starter,
            apiClient = OpencodeApiClient(OkHttpClient(), baseUrl = "http://127.0.0.1:4097"),
            logStore = logStore,
            appDispatchers = AppDispatchers(io = StandardTestDispatcher(testScheduler)),
        )

        controller.reconnectSse()
        advanceUntilIdle()

        val rendered = logStore.entries.value.map { it.renderedText }
        assertThat(rendered.any { it.contains("用户请求重连SSE") }).isTrue()
        assertThat(rendered.any { it.contains("主动断开SSE") }).isTrue()
        assertThat(starter.startCalls).isEqualTo(1)
        assertThat(connection.disconnectCalls).isEqualTo(1)
        assertThat(connection.connectBaseUrls).containsExactly("http://127.0.0.1:4097")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reconnect_usesApiClientBaseUrlWhenCurrentBaseUrlMissing() = runTest {
        val connection = FakeSyncSseConnection(
            currentBaseUrl = null,
            logStore = SyncLogStore(),
        )
        val controller = SyncDebugController(
            syncSseConnection = connection,
            syncSseStarter = FakeSyncSseStarter(),
            apiClient = OpencodeApiClient(OkHttpClient(), baseUrl = "http://127.0.0.1:4097"),
            logStore = SyncLogStore(),
            appDispatchers = AppDispatchers(io = StandardTestDispatcher(testScheduler)),
        )

        controller.reconnectSse()
        advanceUntilIdle()

        assertThat(connection.connectBaseUrls).containsExactly("http://127.0.0.1:4097")
    }

    private class FakeSyncSseConnection(
        override var currentBaseUrl: String?,
        private val logStore: SyncLogStore,
    ) : SyncSseConnection {
        var disconnectCalls = 0
        val connectBaseUrls = mutableListOf<String>()

        override suspend fun connect(baseUrl: String) {
            connectBaseUrls += baseUrl
            currentBaseUrl = baseUrl
        }

        override fun disconnect(traceId: String?) {
            disconnectCalls += 1
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sse,
                title = "主动断开SSE",
                message = "disconnect requested currentBaseUrl=$currentBaseUrl",
                traceId = traceId,
            )
            currentBaseUrl = null
        }
    }

    private class FakeSyncSseStarter : SyncSseStarter {
        var startCalls = 0

        override fun start() {
            startCalls += 1
        }
    }
}
