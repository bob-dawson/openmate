package com.openmate.feature.session.component

import com.google.common.truth.Truth.assertThat
import com.openmate.core.domain.model.TodoInfo
import org.junit.Test

class TodoListCardLogicTest {

    @Test
    fun collapsedTodos_showsOnlyInProgressAndPending_upToThreeItems() {
        val todos = listOf(
            TodoInfo(content = "done-1", status = "completed", priority = "medium"),
            TodoInfo(content = "doing-1", status = "in_progress", priority = "high"),
            TodoInfo(content = "pending-1", status = "pending", priority = "medium"),
            TodoInfo(content = "done-2", status = "completed", priority = "low"),
            TodoInfo(content = "pending-2", status = "pending", priority = "low"),
        )

        val collapsed = collapsedTodos(todos)

        assertThat(collapsed.map { it.content })
            .containsExactly("doing-1", "pending-1", "pending-2")
            .inOrder()
        assertThat(collapsed.map { it.status }.distinct())
            .containsExactly("in_progress", "pending")
    }

    @Test
    fun collapsedTodos_doesNotBackfillCompletedWhenActiveItemsBelowThree() {
        val todos = listOf(
            TodoInfo(content = "doing-1", status = "in_progress", priority = "high"),
            TodoInfo(content = "done-1", status = "completed", priority = "medium"),
            TodoInfo(content = "done-2", status = "completed", priority = "low"),
        )

        val collapsed = collapsedTodos(todos)

        assertThat(collapsed.map { it.content }).containsExactly("doing-1")
    }
}
