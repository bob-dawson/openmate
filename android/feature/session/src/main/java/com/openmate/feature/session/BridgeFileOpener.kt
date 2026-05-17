package com.openmate.feature.session

import android.content.Context
import android.util.Log
import com.openmate.core.common.FileOpener
import com.openmate.core.network.OpencodeApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
    "pdf", "zip", "gz", "tar", "rar", "7z",
    "mp3", "wav", "ogg", "flac", "aac",
    "mp4", "avi", "mkv", "mov", "webm",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "exe", "dll", "so", "dylib",
    "apk", "ipa", "dmg",
)

private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024L

@Singleton
class BridgeFileOpener @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiClient: OpencodeApiClient,
) {
    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    private fun computeLocalPath(filename: String): File {
        cacheDir.mkdirs()
        val safeName = filename.substringAfterLast("/").substringAfterLast("\\")
            .replace("/", "_").replace("\\", "_")
        return File(cacheDir, safeName)
    }

    fun isBinaryFile(path: String): Boolean {
        val ext = path.substringAfterLast(".").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    fun isLargeFile(size: Long): Boolean = size > LARGE_FILE_THRESHOLD

    suspend fun openFile(remotePath: String, onTextPreview: (String) -> Unit, onError: (String) -> Unit) {
        val filename = remotePath.substringAfterLast("/").substringAfterLast("\\")
        if (isBinaryFile(remotePath)) {
            openBinaryFile(remotePath, filename, onError)
        } else {
            openTextFile(remotePath, onTextPreview, onError)
        }
    }

    private suspend fun openTextFile(
        remotePath: String,
        onTextPreview: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                onTextPreview(remotePath)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to read file")
            }
        }
    }

    private suspend fun openBinaryFile(
        remotePath: String,
        filename: String,
        onError: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val stat = apiClient.bridgeStat(remotePath)
                val localFile = computeLocalPath(filename)
                if (!localFile.exists() || localFile.length() != stat.size) {
                    apiClient.bridgeDownloadFile(remotePath, localFile) { _, _ -> }
                }
                withContext(Dispatchers.Main) {
                    FileOpener.openWithSystemViewer(appContext, localFile, filename)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to open file")
            }
        }
    }
}
