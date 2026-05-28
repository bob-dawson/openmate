package com.openmate.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {

    @Test
    fun parseActualGitDiff() {
        val diff = """diff --git a/android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt b/android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt
index ed505e7..fe2fef2 100644
--- a/android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt
+++ b/android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt
@@ -18,6 +18,8 @@ import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
 import androidx.compose.material3.HorizontalDivider
 import androidx.compose.material3.Icon
+import com.openmate.core.data.sync.SyncLogCategory
+import com.openmate.core.data.sync.SyncLogLevel
 import androidx.compose.material3.IconButton
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.Text
@@ -39,6 +41,7 @@ import androidx.lifecycle.ViewModel
 import androidx.lifecycle.viewModelScope
 import com.openmate.core.ui.component.TopBar
 import dagger.hilt.android.lifecycle.HiltViewModel
+import com.openmate.core.domain.model.DiffBuilder
 import com.openmate.core.domain.model.DiffFile
 import com.openmate.core.domain.model.DiffLine
 import com.openmate.core.domain.model.DiffLineType
@@ -311,22 +314,46 @@ data class DiffViewState(
 class DiffViewerViewModel @Inject constructor(
     private val sessionMessageRepository: SessionMessageRepository,
     private val apiClient: OpencodeApiClient,
+    private val logStore: com.openmate.core.data.sync.SyncLogStore,
 ) : ViewModel() {
     val state = mutableStateOf(DiffViewState())
"""
        val files = UnifiedDiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals("android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt", files[0].filePath)
        assertTrue("Expected at least 3 hunks, got ${files[0].hunks.size}", files[0].hunks.size >= 3)
        assertTrue("Expected at least 5 lines, got ${files[0].hunks.sumOf { it.lines.size }}", files[0].hunks.sumOf { it.lines.size } >= 5)

        val firstHunk = files[0].hunks[0]
        assertEquals(18, firstHunk.oldStart)
        assertEquals(18, firstHunk.newStart)

        val addLines = firstHunk.lines.filter { it.type == DiffLineType.ADD }
        assertTrue("Expected add lines in first hunk", addLines.isNotEmpty())
        assertEquals("import com.openmate.core.data.sync.SyncLogCategory", addLines[0].content)
    }

    @Test
    fun parseDiffWithNoColorEscapeCodes() {
        val diff = """[7m@@[0m -18,6 +18,8 @@ import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
+import com.openmate.core.data.sync.SyncLogCategory
 import androidx.compose.material3.MaterialTheme
"""
        val files = UnifiedDiffParser.parse("diff --git a/Foo.kt b/Foo.kt\n--- a/Foo.kt\n+++ b/Foo.kt\n$diff")
        assertEquals(1, files.size)
        assertEquals(0, files[0].hunks.size)
    }

    @Test
    fun parseSingleHunk() {
        val diff = """diff --git a/src/main.rs b/src/main.rs
index 1234567..abcdef0 100644
--- a/src/main.rs
+++ b/src/main.rs
@@ -1,4 +1,5 @@
 fn main() {
     println!("hello");
+    println!("world");
 }
"""
        val files = UnifiedDiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals("src/main.rs", files[0].filePath)
        assertEquals(1, files[0].hunks.size)
        assertEquals(5, files[0].hunks[0].lines.size)
        val addLine = files[0].hunks[0].lines.first { it.type == DiffLineType.ADD }
        assertEquals("    println!(\"world\");", addLine.content)
    }

    @Test
    fun parseMultipleFiles() {
        val diff = """diff --git a/a.rs b/a.rs
--- a/a.rs
+++ b/a.rs
@@ -1,3 +1,3 @@
 line1
-line2
+line2b
 line3
diff --git b/b.rs b/b.rs
--- a/b.rs
+++ b/b.rs
@@ -1,2 +1,3 @@
 foo
+bar
 baz
"""
        val files = UnifiedDiffParser.parse(diff)
        assertEquals(2, files.size)
        assertEquals("a.rs", files[0].filePath)
        assertEquals("b.rs", files[1].filePath)
    }

    @Test
    fun parseEmpty() {
        val files = UnifiedDiffParser.parse("")
        assertTrue(files.isEmpty())
    }

    @Test
    fun parseHunkHeaderWithFunctionName() {
        val diff = """diff --git a/android/app/build.gradle.kts b/android/app/build.gradle.kts
index 0073eea..701d80a 100644
--- a/android/app/build.gradle.kts
+++ b/android/app/build.gradle.kts
@@ -68,6 +68,7 @@ dependencies {
     implementation(project(":core:network"))
     implementation(project(":core:common"))
     implementation(libs.okhttp)
+    implementation(libs.compose.markdown)
     implementation(libs.kstatemachine)
     implementation(libs.kstatemachine.coroutines)
 
"""
        val files = UnifiedDiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals("android/app/build.gradle.kts", files[0].filePath)
        assertEquals(1, files[0].hunks.size)
        assertEquals(8, files[0].hunks[0].lines.size)
        val addLines = files[0].hunks[0].lines.filter { it.type == DiffLineType.ADD }
        assertEquals(1, addLines.size)
        assertEquals("    implementation(libs.compose.markdown)", addLines[0].content)
    }
}
