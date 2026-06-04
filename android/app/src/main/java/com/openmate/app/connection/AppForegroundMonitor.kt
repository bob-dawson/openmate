package com.openmate.app.connection

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface AppForegroundMonitor {
    val isForeground: StateFlow<Boolean>
}

@Singleton
class DefaultAppForegroundMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : AppForegroundMonitor, DefaultLifecycleObserver {
    private val application = context.applicationContext as Application
    private val state = MutableStateFlow(true)
    override val isForeground: StateFlow<Boolean> = state

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        state.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        state.value = false
    }
}
