package com.openmate.core.network

import com.openmate.core.network.dto.ModuleVersion
import com.openmate.core.network.dto.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Named

class VersionClient(
    @param:Named("version") private val versionClient: OkHttpClient,
    @param:Named("release") private val releaseClient: OkHttpClient,
    private val jsdelivrVersionUrl: String = DEFAULT_JSDELIVR_URL,
    private val rawVersionUrl: String = DEFAULT_RAW_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAndroidVersion(): ModuleVersion? = withContext(Dispatchers.IO) {
        fetchManifest(jsdelivrVersionUrl)?.android ?: fetchManifest(rawVersionUrl)?.android
    }

    suspend fun fetchBridgeVersion(): ModuleVersion? = withContext(Dispatchers.IO) {
        fetchManifest(jsdelivrVersionUrl)?.bridge ?: fetchManifest(rawVersionUrl)?.bridge
    }

    private fun fetchManifest(url: String): VersionManifest? = runCatching {
        val request = Request.Builder().url(url).get().build()
        versionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            json.decodeFromString<VersionManifest>(body)
        }
    }.getOrNull()

    suspend fun downloadReleaseAsset(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val response = releaseClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
        }
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val contentLength = body.contentLength()
        destFile.parentFile?.mkdirs()
        body.byteStream().buffered().use { input ->
            destFile.outputStream().buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastProgressTime = System.currentTimeMillis()
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastProgressTime >= 1000) {
                        onProgress.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
                        lastProgressTime = now
                    }
                }
                output.flush()
                onProgress?.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
            }
        }
    }

    companion object {
        const val DEFAULT_JSDELIVR_URL =
            "https://cdn.jsdelivr.net/gh/bob-dawson/openmate@main/version.json"
        const val DEFAULT_RAW_URL =
            "https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json"
    }
}
