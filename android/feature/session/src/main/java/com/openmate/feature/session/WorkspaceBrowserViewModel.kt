package com.openmate.feature.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.CachedFile
import com.openmate.core.domain.repository.FileCacheRepository
import com.openmate.core.network.OpencodeApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DownloadState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String = "",
)

@HiltViewModel
class WorkspaceBrowserViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val apiClient: OpencodeApiClient,
    private val cacheRepo: FileCacheRepository,
    private val dbProvider: ActiveDatabaseProvider,
) : ViewModel() {
    private val TAG = "WorkspaceBrowserVM"

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    private fun profileId(): String {
        return dbProvider.getActiveProfileId() ?: "unknown"
    }

    fun fileCacheDir(): File {
        cacheDir.mkdirs()
        return cacheDir
    }

    suspend fun checkCache(remotePath: String): CachedFile? {
        return cacheRepo.getCachedFile(remotePath, profileId())
    }

    fun downloadAndOpen(
        remotePath: String,
        filename: String,
        fileSize: Long,
        modifiedTime: Long,
        onOpen: (File) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState(downloading = true, totalBytes = fileSize)
            try {
                val subDir = File(cacheDir, remotePath.hashCode().toString())
                subDir.mkdirs()
                val safeName = filename.replace("/", "_").replace("\\", "_")
                val localFile = File(subDir, safeName)
                apiClient.bridgeDownloadFile(remotePath, localFile) { downloaded, total ->
                    _downloadState.value = DownloadState(
                        downloading = true,
                        progress = if (total > 0) downloaded.toFloat() / total else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                    )
                }
                cacheRepo.saveCachedFile(
                    CachedFile(
                        remotePath = remotePath,
                        localPath = localFile.absolutePath,
                        filename = filename,
                        fileSize = localFile.length(),
                        modifiedTime = modifiedTime,
                        profileId = profileId(),
                    )
                )
                _downloadState.value = DownloadState(downloading = false)
                onOpen(localFile)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState(downloading = false, error = e.message ?: "Download failed")
            }
        }
    }

    private var pendingApkFile: File? = null
    private var pendingApkName: String? = null

    fun installApk(file: File, filename: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appContext.packageManager.canRequestPackageInstalls()) {
                openWithSystemViewer(file, filename)
            } else {
                pendingApkFile = file
                pendingApkName = filename
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
        } else {
            openWithSystemViewer(file, filename)
        }
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        pendingApkFile = null
        pendingApkName = null
        openWithSystemViewer(file, name)
    }

    fun openWithSystemViewer(file: File, filename: String) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val ext = filename.substringAfterLast(".", "").lowercase()
        val mime = guessMime(ext)
        val intent = if (ext == "apk") {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            if (ext != "apk") intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No app to open file", e)
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                appContext.startActivity(chooser)
            } catch (_: Exception) {}
        }
    }
}

private fun guessMime(ext: String): String {
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "txt" -> "text/plain"
        "md", "markdown", "mdx" -> "text/markdown"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls", "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
