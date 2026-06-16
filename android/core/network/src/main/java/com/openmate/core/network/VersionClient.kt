package com.openmate.core.network

import com.openmate.core.network.dto.ModuleVersion
import com.openmate.core.network.dto.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
        destFile.parentFile?.mkdirs()

        val totalSize = fetchContentLength(url)
        if (totalSize > 0 && destFile.exists() && destFile.length() == totalSize) {
            onProgress?.invoke(totalSize, totalSize)
            return@withContext
        }

        val existingBytes = if (destFile.exists() && totalSize > 0 && destFile.length() < totalSize) {
            destFile.length()
        } else {
            if (destFile.exists()) destFile.delete()
            0L
        }

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val response = releaseClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw IllegalStateException("HTTP ${response.code}")
        }

        val isPartial = response.code == 206
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val contentLength = body.contentLength()
        val expectedTotal = if (isPartial) totalSize else if (contentLength > 0) contentLength else -1L

        body.byteStream().buffered().use { input ->
            FileOutputStream(destFile, isPartial).buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = existingBytes
                var lastProgressTime = System.currentTimeMillis()
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastProgressTime >= 1000) {
                        onProgress.invoke(downloaded, if (expectedTotal > 0) expectedTotal else downloaded)
                        lastProgressTime = now
                    }
                }
                output.flush()
                onProgress?.invoke(downloaded, if (expectedTotal > 0) expectedTotal else downloaded)
            }
        }
    }

    private fun fetchContentLength(url: String): Long {
        return runCatching {
            val request = Request.Builder().url(url).head().build()
            versionClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching -1L
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = response.header("Accept-Ranges")
                if (acceptRanges == null && contentLength > 0) -1L else contentLength
            }
        }.getOrDefault(-1L)
    }

    companion object {
        const val DEFAULT_JSDELIVR_URL =
            "https://cdn.jsdelivr.net/gh/bob-dawson/openmate@main/version.json"
        const val DEFAULT_RAW_URL =
            "https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json"
    }
}
