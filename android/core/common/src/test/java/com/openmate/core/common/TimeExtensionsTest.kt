package com.openmate.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class TimeExtensionsTest {
    private val now = 1_800_000_000_000L

    @Test
    fun toRelativeTime_justNow() {
        assertThat(now.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.JustNow))
    }

    @Test
    fun toRelativeTime_minutesAgo() {
        val time = now - TimeUnit.MINUTES.toMillis(5)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.Minutes, 5))
    }

    @Test
    fun toRelativeTime_hoursAgo() {
        val time = now - TimeUnit.HOURS.toMillis(3)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.Hours, 3))
    }

    @Test
    fun toRelativeTime_singleDay() {
        val time = now - TimeUnit.DAYS.toMillis(1)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.Days, 1))
    }

    @Test
    fun toRelativeTime_daysAgo() {
        val time = now - TimeUnit.DAYS.toMillis(2)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.Days, 2))
    }

    @Test
    fun toRelativeTime_weeksAgo() {
        val time = now - TimeUnit.DAYS.toMillis(15)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.Weeks, 2))
    }

    @Test
    fun toRelativeTime_futureTimestampIsJustNow() {
        val time = now + TimeUnit.MINUTES.toMillis(10)
        assertThat(time.toRelativeTime(now)).isEqualTo(RelativeTime(RelativeTimeUnit.JustNow))
    }
}
