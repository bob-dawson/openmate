package com.openmate.app.connection

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
) : AppForegroundMonitor, Application.ActivityLifecycleCallbacks {
    private val application = context.applicationContext as Application
    private val state = MutableStateFlow(true)
    override val isForeground: StateFlow<Boolean> = state
    private var startedActivityCount = 0

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount += 1
        if (startedActivityCount > 0) {
            state.value = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0) {
            state.value = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
