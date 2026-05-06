package com.openmate.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.common.FileOpener
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
        val ext = info.name.substringAfterLast('.', "")
        if (ext == "apk") {
            pendingApkFile = info.file
            pendingApkName = info.name
            FileOpener.installApk(appContext, info.file, info.name)
        } else {
            FileOpener.openWithSystemViewer(appContext, info.file, info.name)
        }
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        pendingApkFile = null
        pendingApkName = null
        openFile(CacheFileInfo(file = file, name = name, size = file.length(), lastModified = file.lastModified()))
    }

    fun shareFile(info: CacheFileInfo) {
        FileOpener.shareFile(appContext, info.file, info.name)
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
