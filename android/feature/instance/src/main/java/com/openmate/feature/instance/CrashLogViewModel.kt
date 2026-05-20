package com.openmate.feature.instance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.common.crash.CrashLogManager
import com.openmate.core.common.crash.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CrashLogViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = CrashLogManager(application)

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    private val _selectedContent = MutableStateFlow<String?>(null)
    val selectedContent: StateFlow<String?> = _selectedContent.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        loadReports()
    }

    fun loadReports() {
        viewModelScope.launch(Dispatchers.IO) {
            _reports.value = manager.getReports()
            _unreadCount.value = manager.getUnreadCount()
        }
    }

    fun markAllRead() {
        viewModelScope.launch(Dispatchers.IO) {
            manager.markAllRead()
            _unreadCount.value = 0
        }
    }

    fun viewDetail(timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _selectedContent.value = manager.getReportContent(timestamp)
        }
    }

    fun clearDetail() {
        _selectedContent.value = null
    }

    fun deleteReport(timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.deleteReport(timestamp)
            _reports.value = manager.getReports()
        }
    }

    fun deleteAllReports() {
        viewModelScope.launch(Dispatchers.IO) {
            manager.deleteAllReports()
            _reports.value = emptyList()
        }
    }
}
