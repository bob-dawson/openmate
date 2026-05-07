package com.openmate.feature.session

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.common.FileOpener
import com.openmate.core.database.ActiveDatabaseProvider
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
    private val dbProvider: ActiveDatabaseProvider,
) : ViewModel() {
    private val TAG = "WorkspaceBrowserVM"

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    fun fileCacheDir(): File {
        cacheDir.mkdirs()
        return cacheDir
    }

    private fun computeLocalPath(filename: String): File {
        cacheDir.mkdirs()
        val baseName = filename.substringAfterLast("/").substringAfterLast("\\")
        val safeName = baseName.replace("/", "_").replace("\\", "_")
        return File(cacheDir, safeName)
    }

    fun getCachedFile(remotePath: String, filename: String, expectedSize: Long = -1, expectedModified: Long = -1): File? {
        val localFile = computeLocalPath(filename)
        if (!localFile.exists() || localFile.length() == 0L) return null
        if (expectedSize > 0 && localFile.length() != expectedSize) return null
        if (expectedModified > 0 && kotlin.math.abs(localFile.lastModified() - expectedModified) > 1000) return null
        return localFile
    }

    fun downloadAndOpen(
        remotePath: String,
        filename: String,
        fileSize: Long,
        remoteModified: Long = -1,
        onOpen: (File) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState(downloading = true, totalBytes = fileSize)
            try {
                val localFile = computeLocalPath(filename)
                apiClient.bridgeDownloadFile(remotePath, localFile) { downloaded, total ->
                    _downloadState.value = DownloadState(
                        downloading = true,
                        progress = if (total > 0) downloaded.toFloat() / total else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                    )
                }
                if (remoteModified > 0) localFile.setLastModified(remoteModified)
                _downloadState.value = DownloadState(downloading = false)
                onOpen(localFile)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState(downloading = false, error = e.message ?: "Download failed")
            }
        }
    }

    fun downloadFile(
        remotePath: String,
        filename: String,
        fileSize: Long,
        remoteModified: Long = -1,
        onComplete: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState(downloading = true, totalBytes = fileSize)
            try {
                val localFile = computeLocalPath(filename)
                apiClient.bridgeDownloadFile(remotePath, localFile) { downloaded, total ->
                    _downloadState.value = DownloadState(
                        downloading = true,
                        progress = if (total > 0) downloaded.toFloat() / total else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                    )
                }
                if (remoteModified > 0) localFile.setLastModified(remoteModified)
                _downloadState.value = DownloadState(downloading = false)
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState(downloading = false, error = e.message ?: "Download failed")
            }
        }
    }

    private var pendingApkFile: File? = null
    private var pendingApkName: String? = null

    fun installApk(file: File, filename: String) {
        pendingApkFile = file
        pendingApkName = filename
        FileOpener.installApk(appContext, file, filename)
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        pendingApkFile = null
        pendingApkName = null
        openWithSystemViewer(file, name)
    }

    fun openWithSystemViewer(file: File, filename: String) {
        FileOpener.openWithSystemViewer(appContext, file, filename)
    }

    fun resolveFilename(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex) ?: uri.lastPathSegment ?: "file"
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }
}
