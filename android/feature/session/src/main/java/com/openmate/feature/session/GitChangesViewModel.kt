package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.BridgeGitStatusEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GitChangesState(
    val loading: Boolean = true,
    val files: List<BridgeGitStatusEntry>? = null,
    val error: String? = null,
    val isNotGitRepo: Boolean = false,
)

@HiltViewModel
class GitChangesViewModel @Inject constructor(
    private val apiClient: OpencodeApiClient,
    private val logStore: SyncLogStore,
) : ViewModel() {
    private val TAG = "GitChangesVM"

    private val _state = MutableStateFlow(GitChangesState())
    val state: StateFlow<GitChangesState> = _state.asStateFlow()

    fun loadStatus(directory: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "GitChangesVM loadStatus: directory=$directory")
                val entries = apiClient.bridgeGitStatus(directory)
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "GitChangesVM status entries: count=${entries.size}")
                for (e in entries) {
                    logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "GitChangesVM entry: path=${e.path} status=${e.status}")
                }
                _state.value = GitChangesState(loading = false, files = entries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load git status", e)
                val msg = e.message ?: "Unknown error"
                logStore.log(SyncLogLevel.Error, SyncLogCategory.Poll, "GitChangesVM error: $msg")
                val notGit = msg.contains("404") || msg.contains("not a git repository", ignoreCase = true)
                _state.value = GitChangesState(
                    loading = false,
                    error = if (notGit) null else msg,
                    isNotGitRepo = notGit,
                )
            }
        }
    }
}
