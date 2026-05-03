package com.openmate.feature.session

import androidx.lifecycle.ViewModel
import com.openmate.core.network.OpencodeApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WorkspaceBrowserViewModel @Inject constructor(
    val apiClient: OpencodeApiClient,
) : ViewModel()
