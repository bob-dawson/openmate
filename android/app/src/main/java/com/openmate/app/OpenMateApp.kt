package com.openmate.app

import android.app.Application
import com.openmate.core.common.crash.CrashHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenMateApp : Application() {
    @Inject
    lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        connectionManager.restoreLastConnection()
        connectionManager.startRuntimeMonitoring()
    }
}
