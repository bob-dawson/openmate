package com.openmate.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.database.ActiveDatabaseProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
) : ViewModel() {

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _syncTimeRange = MutableStateFlow(SyncTimeRange.TWO_WEEKS)
    val syncTimeRange: StateFlow<SyncTimeRange> = _syncTimeRange.asStateFlow()

    init {
        loadActiveProfile()
    }

    private fun loadActiveProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = dbProvider.getActiveProfileId()
            if (profileId != null) {
                _activeProfile.value = profileRepository.getById(profileId)
            }
        }
    }

    fun setSyncTimeRange(range: SyncTimeRange) {
        _syncTimeRange.value = range
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = dbProvider.getActiveProfileId()
            if (profileId != null) {
                dbProvider.clearActive()
                dbProvider.setActive(profileId)
            }
        }
    }

    fun disconnect() {
        sseEventRepository.disconnect()
        dbProvider.clearActive()
    }
}

enum class SyncTimeRange(val days: Int, val label: String) {
    ONE_WEEK(7, "1 Week"),
    TWO_WEEKS(14, "2 Weeks"),
    ONE_MONTH(30, "1 Month"),
    THREE_MONTHS(90, "3 Months"),
}
