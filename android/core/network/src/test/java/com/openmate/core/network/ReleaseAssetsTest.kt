package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class ReleaseAssetsTest {

    private val github = "https://github.com/bob-dawson/openmate/releases/download"
    private val atomgit = "https://atomgit.com/article88/openmate/releases/download"

    @Test
    fun apkFilename_constructsCorrectName() {
        assertThat(ReleaseAssets.apkFilename("v0.1.20")).isEqualTo("OpenMate-0.1.20.apk")
    }

    @Test
    fun regionKey_usesRegionThenLanguage() {
        assertThat(ReleaseAssets.regionKey(Locale.forLanguageTag("zh-CN"))).isEqualTo("cn")
        assertThat(ReleaseAssets.regionKey(Locale.forLanguageTag("zh"))).isEqualTo("cn")
        assertThat(ReleaseAssets.regionKey(Locale.forLanguageTag("zh-Hans"))).isEqualTo("cn")
        assertThat(ReleaseAssets.regionKey(Locale.forLanguageTag("zh-TW"))).isEqualTo("default")
        assertThat(ReleaseAssets.regionKey(Locale.forLanguageTag("en-US"))).isEqualTo("default")
    }

    @Test
    fun apkUrls_cnLocale_prefersAtomGitThenGitHub() {
        val urls = ReleaseAssets.apkUrls("v0.3.4", null, Locale.forLanguageTag("zh-CN"))
        assertThat(urls).containsExactly(
            "$atomgit/v0.3.4/OpenMate-0.3.4.apk",
            "$github/v0.3.4/OpenMate-0.3.4.apk",
        ).inOrder()
    }

    @Test
    fun apkUrls_defaultLocale_prefersGitHub() {
        val urls = ReleaseAssets.apkUrls("v0.3.4", null, Locale.forLanguageTag("en-US"))
        assertThat(urls.first()).isEqualTo("$github/v0.3.4/OpenMate-0.3.4.apk")
    }

    @Test
    fun apkUrls_usesConfiguredMirrors() {
        val mirrors = mapOf(
            "cn" to listOf(atomgit, github),
            "default" to listOf(github),
        )
        assertThat(ReleaseAssets.apkUrls("v0.3.4", mirrors, Locale.forLanguageTag("zh-CN")).first())
            .startsWith(atomgit)
        assertThat(ReleaseAssets.apkUrls("v0.3.4", mirrors, Locale.forLanguageTag("en-US")))
            .containsExactly("$github/v0.3.4/OpenMate-0.3.4.apk")
    }

    @Test
    fun apkUrls_missingRegion_fallsBackToDefaultKey() {
        val mirrors = mapOf("default" to listOf(github, atomgit))
        assertThat(ReleaseAssets.apkUrls("v0.3.4", mirrors, Locale.forLanguageTag("zh-CN")))
            .containsExactly(
                "$github/v0.3.4/OpenMate-0.3.4.apk",
                "$atomgit/v0.3.4/OpenMate-0.3.4.apk",
            ).inOrder()
    }
}
