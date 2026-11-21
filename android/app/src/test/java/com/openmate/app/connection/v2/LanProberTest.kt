package com.openmate.app.connection.v2

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class LanProberTest {

    private lateinit var server: MockWebServer
    private lateinit var prober: LanProber

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prober = LanProber(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun candidate(instanceId: String = "inst-1"): LanCandidate {
        return LanCandidate(
            ip = server.hostName,
            port = server.port,
            scope = LanCandidateRanking.SCOPE_PRIVATE,
        )
    }

    private fun statusBody(instanceId: String): String =
        """{"bridge":{"version":"0.3.26","port":${server.port},"instance_id":"$instanceId"}}"""

    @Test
    fun probeBest_returnsCandidateWhenInstanceMatches() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody("inst-1")))

        val result = prober.probeBest("inst-1", listOf(candidate()))

        assertThat(result?.port).isEqualTo(server.port)
        assertThat(server.takeRequest().path).isEqualTo("/api/bridge/status")
    }

    @Test
    fun probeBest_rejectsMismatchedInstance() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody("another-bridge")))

        val result = prober.probeBest("inst-1", listOf(candidate()))

        assertThat(result).isNull()
    }

    @Test
    fun probeBest_acceptsWhenExpectedInstanceIsBlank() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody("whatever")))

        val result = prober.probeBest("", listOf(candidate()))

        assertThat(result).isNotNull()
    }

    @Test
    fun probeBest_returnsNullWhenBridgeUnreachable() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = prober.probeBest("inst-1", listOf(candidate()))

        assertThat(result).isNull()
    }

    @Test
    fun probeBest_returnsEmptyForEmptyCandidates() = runTest {
        assertThat(prober.probeBest("inst-1", emptyList())).isNull()
    }
}
