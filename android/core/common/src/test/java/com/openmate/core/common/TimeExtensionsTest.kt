package com.openmate.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeExtensionsTest {
    @Test
    fun toRelativeTimeString_justNow() {
        val now = System.currentTimeMillis()
        assertThat(now.toRelativeTimeString()).isEqualTo("just now")
    }

    @Test
    fun toRelativeTimeString_minutesAgo() {
        val time = System.currentTimeMillis() - 5 * 60 * 1000
        assertThat(time.toRelativeTimeString()).isEqualTo("5 min ago")
    }

    @Test
    fun toRelativeTimeString_hoursAgo() {
        val time = System.currentTimeMillis() - 3 * 60 * 60 * 1000
        assertThat(time.toRelativeTimeString()).isEqualTo("3 hr ago")
    }

    @Test
    fun toRelativeTimeString_daysAgo() {
        val time = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        assertThat(time.toRelativeTimeString()).isEqualTo("2 days ago")
    }

    @Test
    fun toRelativeTimeString_singleDay() {
        val time = System.currentTimeMillis() - 1 * 24 * 60 * 60 * 1000L
        assertThat(time.toRelativeTimeString()).isEqualTo("1 day ago")
    }
}
