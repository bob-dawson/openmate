package com.openmate.core.common

import android.content.Context

object AppInfo {
    fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
}
