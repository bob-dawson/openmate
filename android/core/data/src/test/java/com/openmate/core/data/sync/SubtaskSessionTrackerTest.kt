package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtaskSessionTrackerTest {

    @Test
    fun record_mapsCallIdToChildSession() {
        val tracker = SubtaskSessionTracker()
        tracker.record("call_1", "ses_child")
        assertThat(tracker.byCallId.value["call_1"]).isEqualTo("ses_child")
    }

    @Test
    fun record_ignoresBlankValues() {
        val tracker = SubtaskSessionTracker()
        tracker.record("", "ses_child")
        tracker.record("call_1", "")
        assertThat(tracker.byCallId.value).isEmpty()
    }

    @Test
    fun clear_removesAllMappings() {
        val tracker = SubtaskSessionTracker()
        tracker.record("call_1", "ses_child")
        tracker.clear()
        assertThat(tracker.byCallId.value).isEmpty()
    }
}
