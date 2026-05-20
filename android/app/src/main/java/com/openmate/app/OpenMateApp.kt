package com.openmate.app

import android.app.Application
import com.openmate.core.common.crash.CrashHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenMateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
