package com.openmate.feature.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class CacheManagerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun refresh_displaysNewCachedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheDir = context.cacheDir.resolve("file_cache")
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        val viewModel = CacheManagerViewModel(context)

        composeRule.setContent {
            CacheManagerScreen(
                onBack = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.no_cached_files)).assertIsDisplayed()

        val nestedDir = cacheDir.resolve("12345")
        nestedDir.mkdirs()
        nestedDir.resolve("sample.txt").writeText("cached")

        composeRule.runOnIdle {
            viewModel.refresh()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("sample.txt")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("sample.txt").assertIsDisplayed()
    }
}
