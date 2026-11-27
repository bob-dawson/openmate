package com.openmate.core.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppFontScaleTest {

    @Test
    fun factorsAreStandardLargeExtraLarge() {
        assertThat(AppFontScale.STANDARD.factor).isEqualTo(1.0f)
        assertThat(AppFontScale.LARGE.factor).isEqualTo(1.25f)
        assertThat(AppFontScale.EXTRA_LARGE.factor).isEqualTo(1.5f)
    }

    @Test
    fun storageKeysRoundTrip() {
        AppFontScale.entries.forEach { scale ->
            assertThat(AppFontScale.fromKey(scale.storageKey)).isEqualTo(scale)
        }
    }

    @Test
    fun unknownOrMissingKeyFallsBackToStandard() {
        assertThat(AppFontScale.fromKey(null)).isEqualTo(AppFontScale.STANDARD)
        assertThat(AppFontScale.fromKey("")).isEqualTo(AppFontScale.STANDARD)
        assertThat(AppFontScale.fromKey("huge")).isEqualTo(AppFontScale.STANDARD)
    }

    @Test
    fun indexMapsToSliderStops() {
        assertThat(AppFontScale.fromIndex(0)).isEqualTo(AppFontScale.STANDARD)
        assertThat(AppFontScale.fromIndex(1)).isEqualTo(AppFontScale.LARGE)
        assertThat(AppFontScale.fromIndex(2)).isEqualTo(AppFontScale.EXTRA_LARGE)
        assertThat(AppFontScale.fromIndex(9)).isEqualTo(AppFontScale.STANDARD)
    }
}
