package com.openmate.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CacheFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
)

enum class CacheSortField { NAME, SIZE, MODIFIED }

@HiltViewModel
class CacheManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val TAG = "CacheManagerVM"

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    private val _cacheFiles = MutableStateFlow<List<CacheFileInfo>>(emptyList())
    val cacheFiles: StateFlow<List<CacheFileInfo>> = _cacheFiles.asStateFlow()

    private val _sortField = MutableStateFlow(CacheSortField.NAME)
    val sortField: StateFlow<CacheSortField> = _sortField.asStateFlow()

    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var pendingApkFile: File? = null
    private var pendingApkName: String? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!cacheDir.exists()) {
                _cacheFiles.value = emptyList()
                return@launch
            }
            val files = mutableListOf<CacheFileInfo>()
            cacheDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    files.add(
                        CacheFileInfo(
                            file = file,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                        )
                    )
                }
            }
            _cacheFiles.value = files
        }
    }

    fun setSortField(field: CacheSortField) {
        if (_sortField.value == field) {
            _sortAscending.value = !_sortAscending.value
        } else {
            _sortField.value = field
            _sortAscending.value = true
        }
    }

    fun sortedFiles(): List<CacheFileInfo> {
        val files = _cacheFiles.value
        val asc = _sortAscending.value
        return when (_sortField.value) {
            CacheSortField.NAME -> if (asc) files.sortedBy { it.name.lowercase() } else files.sortedByDescending { it.name.lowercase() }
            CacheSortField.SIZE -> if (asc) files.sortedBy { it.size } else files.sortedByDescending { it.size }
            CacheSortField.MODIFIED -> if (asc) files.sortedBy { it.lastModified } else files.sortedByDescending { it.lastModified }
        }
    }

    fun openFile(info: CacheFileInfo) {
        val ext = info.name.substringAfterLast(".").lowercase()
        if (ext == "apk") {
            installApk(info)
        } else {
            openWithSystemViewer(info)
        }
    }

    fun installApk(info: CacheFileInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appContext.packageManager.canRequestPackageInstalls()) {
                openWithSystemViewer(info)
            } else {
                pendingApkFile = info.file
                pendingApkName = info.name
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
        } else {
            openWithSystemViewer(info)
        }
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        pendingApkFile = null
        pendingApkName = null
        openWithSystemViewer(CacheFileInfo(file = file, name = name, size = file.length(), lastModified = file.lastModified()))
    }

    private fun openWithSystemViewer(info: CacheFileInfo) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", info.file)
        val ext = info.name.substringAfterLast(".").lowercase()
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No app to open file", e)
            val chooser = Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                appContext.startActivity(chooser)
            } catch (_: Exception) {
                _errorMessage.value = appContext.getString(R.string.no_app_to_open)
            }
        }
    }

    fun shareFile(info: CacheFileInfo) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", info.file)
        val ext = info.name.substringAfterLast(".").lowercase()
        val mime = guessMime(ext)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot share file", e)
            _errorMessage.value = appContext.getString(R.string.cannot_share)
        }
    }

    fun deleteFile(info: CacheFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            info.file.delete()
            val parent = info.file.parentFile
            if (parent != null && parent != cacheDir && parent.listFiles().isNullOrEmpty()) {
                parent.delete()
            }
            refresh()
        }
    }

    fun renameFile(info: CacheFileInfo, newName: String) {
        if (newName.isBlank() || newName == info.name) return
        viewModelScope.launch(Dispatchers.IO) {
            val newFile = File(info.file.parentFile, newName)
            if (newFile.exists()) {
                _errorMessage.value = appContext.getString(R.string.file_already_exists)
                return@launch
            }
            val success = info.file.renameTo(newFile)
            if (!success) {
                _errorMessage.value = appContext.getString(R.string.rename_failed)
                return@launch
            }
            refresh()
        }
    }

    fun clearError() {
        _errorMessage.value = null
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
