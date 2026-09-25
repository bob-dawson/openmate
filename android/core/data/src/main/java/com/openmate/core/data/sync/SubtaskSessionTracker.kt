package com.openmate.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtaskSessionTracker @Inject constructor() {
    private val _byCallId = MutableStateFlow<Map<String, String>>(emptyMap())
    val byCallId: StateFlow<Map<String, String>> = _byCallId.asStateFlow()

    fun record(callId: String, childSessionId: String) {
        if (callId.isBlank() || childSessionId.isBlank()) return
        _byCallId.update { it + (callId to childSessionId) }
    }

    fun clear() {
        _byCallId.value = emptyMap()
    }
}
