package com.openmate.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MyersDiffTest {

    @Test
    fun singleLineChange_producesRemoveAndAdd() {
        val lines = MyersDiff.diff(listOf("a"), listOf("b"))
        assertThat(lines.filter { it.type == DiffLineType.REMOVE }.map { it.content }).containsExactly("a")
        assertThat(lines.filter { it.type == DiffLineType.ADD }.map { it.content }).containsExactly("b")
    }

    @Test
    fun middleChange_keepsContext() {
        val lines = MyersDiff.diff(listOf("a", "b", "c"), listOf("a", "x", "c"))
        assertThat(lines.filter { it.type == DiffLineType.CONTEXT }.map { it.content }).containsExactly("a", "c")
        assertThat(lines.filter { it.type == DiffLineType.REMOVE }.map { it.content }).containsExactly("b")
        assertThat(lines.filter { it.type == DiffLineType.ADD }.map { it.content }).containsExactly("x")
    }

    @Test
    fun largerEdit_doesNotThrowAndFindsMinimalEdits() {
        val old = (1..14).map { "line-$it" }
        val new = old.toMutableList().apply {
            this[3] = "changed-4"
            add(7, "inserted")
        }
        val lines = MyersDiff.diff(old, new)
        assertThat(lines.filter { it.type == DiffLineType.REMOVE }.map { it.content }).containsExactly("line-4")
        assertThat(lines.filter { it.type == DiffLineType.ADD }.map { it.content })
            .containsExactly("changed-4", "inserted")
    }

    @Test
    fun emptyOld_producesOnlyAdds() {
        val lines = MyersDiff.diff(emptyList(), listOf("x", "y"))
        assertThat(lines.map { it.type }).containsExactly(DiffLineType.ADD, DiffLineType.ADD).inOrder()
    }

    @Test
    fun emptyNew_producesOnlyRemoves() {
        val lines = MyersDiff.diff(listOf("x", "y"), emptyList())
        assertThat(lines.map { it.type }).containsExactly(DiffLineType.REMOVE, DiffLineType.REMOVE).inOrder()
    }

    @Test
    fun editFallback_buildsHunks() {
        val file = DiffBuilder.fromEditFallback("/tmp/a.txt", "old line", "new line")
        assertThat(file).isNotNull()
        assertThat(file!!.filePath).isEqualTo("/tmp/a.txt")
        assertThat(file.hunks).isNotEmpty()
    }
}
