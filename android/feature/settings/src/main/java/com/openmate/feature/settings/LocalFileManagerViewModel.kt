package com.openmate.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.openmate.core.common.FileOpener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

data class LocalFileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

enum class LocalSortField { NAME, SIZE, MODIFIED }

@HiltViewModel
class LocalFileManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val TAG = "LocalFileManagerVM"

    private val rootDir get() = File(appContext.cacheDir, "file_cache")

    private val _currentDir = MutableStateFlow(rootDir)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _entries = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val entries: StateFlow<List<LocalFileEntry>> = _entries.asStateFlow()

    private val _sortField = MutableStateFlow(LocalSortField.NAME)
    val sortField: StateFlow<LocalSortField> = _sortField.asStateFlow()

    private val _sortAsc = MutableStateFlow(true)
    val sortAsc: StateFlow<Boolean> = _sortAsc.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var pendingApkFile: File? = null
        private set
    var pendingApkName: String? = null
        private set

    fun refresh() {
        val dir = _currentDir.value
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val files = dir.listFiles()?.toList() ?: emptyList()
        _entries.value = files.map { file ->
            LocalFileEntry(
                file = file,
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
            )
        }.let { entries ->
            val sorted = when (_sortField.value) {
                LocalSortField.NAME -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                LocalSortField.SIZE -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.size }
                )
                LocalSortField.MODIFIED -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.lastModified }
                )
            }
            if (_sortAsc.value) sorted else sorted.reversed()
        }
    }

    fun setSortField(field: LocalSortField) {
        if (_sortField.value == field) {
            _sortAsc.value = !_sortAsc.value
        } else {
            _sortField.value = field
            _sortAsc.value = true
        }
        refresh()
    }

    fun navigateInto(name: String) {
        val target = File(_currentDir.value, name)
        if (target.isDirectory) {
            _currentDir.value = target
            refresh()
        }
    }

    fun navigateUp() {
        val parent = _currentDir.value.parentFile
        if (parent != null && parent.startsWith(rootDir)) {
            _currentDir.value = parent
            refresh()
        }
    }

    fun isAtRoot(): Boolean = _currentDir.value == rootDir

    fun relativePath(): String {
        return _currentDir.value.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
    }

    fun createDirectory(name: String) {
        val dir = File(_currentDir.value, name)
        if (dir.exists()) {
            _errorMessage.value = "Directory already exists: $name"
            return
        }
        dir.mkdirs()
        refresh()
    }

    fun moveFile(entry: LocalFileEntry, targetDir: File) {
        val dest = File(targetDir, entry.name)
        if (dest.exists()) {
            _errorMessage.value = "File already exists: ${entry.name}"
            return
        }
        val moved = entry.file.renameTo(dest)
        if (!moved) {
            _errorMessage.value = "Failed to move ${entry.name}"
        }
        refresh()
    }

    fun openFile(entry: LocalFileEntry) {
        if (entry.isDirectory) return
        val ext = entry.name.substringAfterLast('.', "")
        if (ext == "apk") {
            pendingApkFile = entry.file
            pendingApkName = entry.name
            FileOpener.installApk(appContext, entry.file, entry.name)
        } else {
            FileOpener.openWithSystemViewer(appContext, entry.file, entry.name)
        }
    }

    fun shareFile(entry: LocalFileEntry) {
        if (entry.isDirectory) return
        FileOpener.shareFile(appContext, entry.file, entry.name)
    }

    fun deleteFile(entry: LocalFileEntry) {
        entry.file.deleteRecursively()
        refresh()
    }

    fun renameFile(entry: LocalFileEntry, newName: String) {
        val dest = File(entry.file.parentFile, newName)
        if (dest.exists()) {
            _errorMessage.value = "Already exists: $newName"
            return
        }
        val renamed = entry.file.renameTo(dest)
        if (!renamed) {
            _errorMessage.value = "Rename failed"
        }
        refresh()
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        FileOpener.installApk(appContext, file, name)
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
