package com.openmate.core.network

object ReleaseAssets {
    private const val BASE_URL = "https://github.com/bob-dawson/openmate/releases/download"

    fun apkFilename(tag: String): String {
        val version = tag.trimStart('v')
        return "OpenMate-$version.apk"
    }

    fun apkUrl(tag: String): String = "$BASE_URL/$tag/${apkFilename(tag)}"
}
