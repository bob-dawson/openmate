package com.openmate.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openmate.core.common.RelativeTime
import com.openmate.core.common.RelativeTimeUnit
import com.openmate.core.ui.R

@Composable
fun RelativeTime.asText(): String = when (unit) {
    RelativeTimeUnit.JustNow -> stringResource(R.string.time_just_now)
    RelativeTimeUnit.Minutes -> stringResource(R.string.time_minutes_ago, value)
    RelativeTimeUnit.Hours -> stringResource(R.string.time_hours_ago, value)
    RelativeTimeUnit.Days -> stringResource(R.string.time_days_ago, value)
    RelativeTimeUnit.Weeks -> stringResource(R.string.time_weeks_ago, value)
}
