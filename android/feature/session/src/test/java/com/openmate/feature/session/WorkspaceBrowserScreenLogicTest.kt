package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import com.openmate.core.network.dto.BridgeSearchResultDto
import com.openmate.feature.session.component.shouldShowSearchSortHeader
import org.junit.Test

class WorkspaceBrowserScreenLogicTest {

    @Test
    fun shouldShowSearchSortHeader_returnsTrueForFilenameResults() {
        assertThat(
            shouldShowSearchSortHeader(
                selectedTab = 0,
                filenameQuery = "mai",
                filenameResults = listOf(result("/workspace/Main.kt")),
                contentSearching = false,
                contentResults = emptyList(),
            )
        ).isTrue()
    }

    @Test
    fun shouldShowSearchSortHeader_returnsTrueForContentResults() {
        assertThat(
            shouldShowSearchSortHeader(
                selectedTab = 1,
                filenameQuery = "",
                filenameResults = emptyList(),
                contentSearching = false,
                contentResults = listOf(result("/workspace/README.md")),
            )
        ).isTrue()
    }

    @Test
    fun shouldShowSearchSortHeader_returnsFalseWhileContentSearchRunning() {
        assertThat(
            shouldShowSearchSortHeader(
                selectedTab = 1,
                filenameQuery = "",
                filenameResults = emptyList(),
                contentSearching = true,
                contentResults = listOf(result("/workspace/README.md")),
            )
        ).isFalse()
    }

    private fun result(path: String) = BridgeSearchResultDto(
        path = path,
        isDirectory = false,
        size = 1,
        modified = 1,
        snippet = null,
    )
}
