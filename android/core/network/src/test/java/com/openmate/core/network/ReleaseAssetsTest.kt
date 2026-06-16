package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseAssetsTest {

    @Test
    fun apkUrl_constructsCorrectUrl() {
        val url = ReleaseAssets.apkUrl("v0.1.20")
        assertThat(url).isEqualTo(
            "https://github.com/bob-dawson/openmate/releases/download/v0.1.20/OpenMate-0.1.20.apk"
        )
    }

    @Test
    fun apkFilename_constructsCorrectName() {
        assertThat(ReleaseAssets.apkFilename("v0.1.20")).isEqualTo("OpenMate-0.1.20.apk")
    }
}
