package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionTest {
    @Test
    fun create_withRequiredFields() {
        val session = Session(
            id = "s-1",
            title = "Test Session",
            directory = "/home/project",
            projectID = "proj-1",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L,
        )
        assertThat(session.id).isEqualTo("s-1")
        assertThat(session.workspaceID).isNull()
        assertThat(session.parentID).isNull()
        assertThat(session.isCompacting).isFalse()
        assertThat(session.isArchived).isFalse()
        assertThat(session.status).isNull()
    }

    @Test
    fun create_withAllFields() {
        val session = Session(
            id = "s-2",
            title = "Full Session",
            directory = "/home/project",
            projectID = "proj-1",
            workspaceID = "ws-1",
            parentID = "s-1",
            createdAt = 1700000000000L,
            updatedAt = 1700001000000L,
            isCompacting = true,
            isArchived = true,
            status = SessionStatus.BUSY,
        )
        assertThat(session.workspaceID).isEqualTo("ws-1")
        assertThat(session.status).isEqualTo(SessionStatus.BUSY)
        assertThat(session.isCompacting).isTrue()
    }

    @Test
    fun sessionStatus_values() {
        assertThat(SessionStatus.values()).asList()
            .containsExactly(SessionStatus.IDLE, SessionStatus.BUSY, SessionStatus.COMPACTING)
    }
}
