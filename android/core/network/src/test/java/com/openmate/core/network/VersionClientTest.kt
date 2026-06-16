package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class VersionClientTest {

    private lateinit var jsdelivrServer: MockWebServer
    private lateinit var rawServer: MockWebServer
    private lateinit var downloadServer: MockWebServer

    @Before
    fun setUp() {
        jsdelivrServer = MockWebServer(); jsdelivrServer.start()
        rawServer = MockWebServer(); rawServer.start()
        downloadServer = MockWebServer(); downloadServer.start()
    }

    @After
    fun tearDown() {
        jsdelivrServer.shutdown(); rawServer.shutdown(); downloadServer.shutdown()
    }

    private fun client(jsdelivrUrl: String, rawUrl: String): VersionClient =
        VersionClient(
            versionClient = OkHttpClient(),
            releaseClient = OkHttpClient(),
            jsdelivrVersionUrl = jsdelivrUrl,
            rawVersionUrl = rawUrl,
        )

    @Test
    fun fetchAndroidVersion_jsdelivrSucceeds_returnsVersion() = runBlocking {
        jsdelivrServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"android":{"version":"0.1.20","tag":"v0.1.20","releasedAt":"2026-06-20"}}""")
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result?.version).isEqualTo("0.1.20")
        assertThat(rawServer.requestCount).isEqualTo(0)
    }

    @Test
    fun fetchAndroidVersion_jsdelivrFails_fallsBackToRaw() = runBlocking {
        jsdelivrServer.enqueue(MockResponse().setResponseCode(500))
        rawServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"android":{"version":"0.1.20","tag":"v0.1.20"}}""")
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result?.version).isEqualTo("0.1.20")
    }

    @Test
    fun fetchAndroidVersion_bothFail_returnsNull() = runBlocking {
        jsdelivrServer.enqueue(MockResponse().setResponseCode(500))
        rawServer.enqueue(MockResponse().setResponseCode(500))
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result).isNull()
    }

    @Test
    fun downloadReleaseAsset_writesFileAndReportsProgress() = runBlocking<Unit> {
        val bytes = ByteArray(100) { it.toByte() }
        downloadServer.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes))
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val dest = File.createTempFile("test", ".apk").apply { delete() }
        var lastDownloaded = 0L
        c.downloadReleaseAsset(
            url = downloadServer.url("/asset").toString(),
            destFile = dest,
            onProgress = { downloaded, _ -> lastDownloaded = downloaded }
        )
        assertThat(dest.exists()).isTrue()
        assertThat(dest.length()).isEqualTo(100L)
        assertThat(lastDownloaded).isAtLeast(100L)
        dest.delete()
    }
}
