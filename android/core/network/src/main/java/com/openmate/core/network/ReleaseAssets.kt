package com.openmate.core.network

import java.util.Locale

object ReleaseAssets {
    private const val GITHUB_BASE = "https://github.com/bob-dawson/openmate/releases/download"
    private const val ATOMGIT_BASE = "https://atomgit.com/article88/openmate/releases/download"

    /** Used when version.json does not provide a mirror list. */
    private val DEFAULT_MIRRORS: Map<String, List<String>> = mapOf(
        "cn" to listOf(ATOMGIT_BASE, GITHUB_BASE),
        "default" to listOf(GITHUB_BASE, ATOMGIT_BASE),
    )

    fun apkFilename(tag: String): String {
        val version = tag.trimStart('v')
        return "OpenMate-$version.apk"
    }

    /** Mirror base URLs for the current OS region, most-preferred first. */
    fun mirrorBases(mirrors: Map<String, List<String>>? = null, locale: Locale = Locale.getDefault()): List<String> {
        val configured = mirrors?.takeIf { it.isNotEmpty() } ?: DEFAULT_MIRRORS
        return configured[regionKey(locale)]
            ?: configured["default"]?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MIRRORS.getValue("default")
    }

    /** Candidate download URLs for the APK of [tag], most-preferred first. */
    fun apkUrls(
        tag: String,
        mirrors: Map<String, List<String>>? = null,
        locale: Locale = Locale.getDefault(),
    ): List<String> {
        val filename = apkFilename(tag)
        return mirrorBases(mirrors, locale).map { "$it/$tag/$filename" }
    }

    /** "cn" when the OS region is China (falling back to language), else "default". */
    fun regionKey(locale: Locale = Locale.getDefault()): String {
        val country = locale.country.uppercase(Locale.ROOT)
        if (country == "CN") return "cn"
        if (country.isEmpty() && locale.language.lowercase(Locale.ROOT) == "zh") return "cn"
        return "default"
    }
}
