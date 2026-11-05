package com.openmate.core.network.dto

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.SessionStatus
import org.junit.Test

class SessionStatusDtoTest {

    @Test
    fun toDomain_mapsRunningToRunning() {
        assertThat(SessionStatusDto(type = "running").toDomain()).isEqualTo(SessionStatus.RUNNING)
    }

    @Test
    fun toDomain_mapsBusyAndRetryToBusy() {
        assertThat(SessionStatusDto(type = "busy").toDomain()).isEqualTo(SessionStatus.BUSY)
        assertThat(SessionStatusDto(type = "retry").toDomain()).isEqualTo(SessionStatus.BUSY)
    }

    @Test
    fun toDomain_mapsIdleAndUnknownToIdle() {
        assertThat(SessionStatusDto(type = "idle").toDomain()).isEqualTo(SessionStatus.IDLE)
        assertThat(SessionStatusDto(type = "").toDomain()).isEqualTo(SessionStatus.IDLE)
        assertThat(SessionStatusDto(type = "something-new").toDomain()).isEqualTo(SessionStatus.IDLE)
    }
}
