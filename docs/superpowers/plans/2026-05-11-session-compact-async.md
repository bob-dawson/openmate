# Session Compact Async Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android session compact fire-and-forget like Web, with compact button state driven entirely by synced session status, and render compact summary content as Markdown.

**Architecture:** Keep the existing `/session/{sessionID}/summarize` endpoint, but remove Android's local compact request state and fixed completion delay. Let existing synchronized session state determine compact button behavior, and reuse the app's existing Markdown rendering component for compact summaries.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, StateFlow, Robolectric unit tests, Compose Android UI tests, Google Truth, `dev.jeziellago.compose.markdowntext.MarkdownText`

---

### Task 1: Remove local compact request state from the ViewModel

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing compact fire-and-forget test**

Add a new test near the other `SessionDetailViewModelTest` cases:

```kotlin
@Test
fun compact_startsSummarizeWithoutWaitingForCompletionSync() = runTest(dispatcher) {
    val apiClient = FakeOpencodeApiClient()
    val repository = FakeSessionMessageRepository(
        recentWindow = listOf(message("m1", timeCreated = 1)),
        lastSeq = null,
    )
    val viewModel = createViewModel(
        sessionMessageRepository = repository,
        apiClient = apiClient,
    )

    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.messages.value.isNotEmpty() }
    viewModel.selectModel("openai", "gpt-5", "gpt-5")

    viewModel.compact(SESSION_ID)
    advanceUntilIdle()

    assertThat(apiClient.summarizeCalls).containsExactly(
        FakeOpencodeApiClient.SummarizeCall(
            sessionId = SESSION_ID,
            providerId = "openai",
            modelId = "gpt-5",
            directory = null,
        ),
    )
    assertThat(repository.incrementalSyncCalls).isEmpty()
    assertThat(repository.incrementalSyncAndNotifyCalls).isEmpty()
}
```

- [ ] **Step 2: Run the new unit test to verify it fails for the right reason**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.compact_startsSummarizeWithoutWaitingForCompletionSync --no-daemon`

Expected: FAIL because `compact()` still performs delayed sync work after the summarize call.

- [ ] **Step 3: Add a fake API call recorder if the test helpers do not already expose one**

Update the fake client in `SessionDetailViewModelTest.kt` to track summarize calls:

```kotlin
data class SummarizeCall(
    val sessionId: String,
    val providerId: String,
    val modelId: String,
    val directory: String?,
)

val summarizeCalls = mutableListOf<SummarizeCall>()

override suspend fun summarizeSession(
    sessionID: String,
    providerID: String,
    modelID: String,
    directory: String?,
) {
    summarizeCalls += SummarizeCall(sessionID, providerID, modelID, directory)
}
```

- [ ] **Step 4: Implement the minimal fire-and-forget compact change in the ViewModel**

Replace the current compact implementation with a request-only version:

```kotlin
fun compact(sessionID: String) {
    val model = _selectedModel.value ?: return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            apiClient.summarizeSession(
                sessionID,
                model.providerID,
                model.modelID,
                currentDirectory.ifBlank { null },
            )
        } catch (e: Exception) {
            Log.e(TAG, "compact failed", e)
            _errorMessage.value = "Compact failed: ${e.message}"
        }
    }
}
```

Also remove the local compact state members and imports that become unused:

```kotlin
// Remove
private val _isCompacting = MutableStateFlow(false)
val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

// Remove if no longer used
import kotlinx.coroutines.delay
```

- [ ] **Step 5: Run the compact fire-and-forget unit test to verify it passes**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.compact_startsSummarizeWithoutWaitingForCompletionSync --no-daemon`

Expected: PASS

- [ ] **Step 6: Add a failure-path unit test for summarize startup errors**

Add this test in `SessionDetailViewModelTest.kt`:

```kotlin
@Test
fun compact_surfacesStartupFailure() = runTest(dispatcher) {
    val apiClient = FakeOpencodeApiClient().apply {
        summarizeError = IllegalStateException("network down")
    }
    val repository = FakeSessionMessageRepository(
        recentWindow = listOf(message("m1", timeCreated = 1)),
        lastSeq = null,
    )
    val viewModel = createViewModel(
        sessionMessageRepository = repository,
        apiClient = apiClient,
    )

    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.messages.value.isNotEmpty() }
    viewModel.selectModel("openai", "gpt-5", "gpt-5")

    viewModel.compact(SESSION_ID)
    waitUntil { viewModel.errorMessage.value != null }

    assertThat(viewModel.errorMessage.value).contains("network down")
}
```

- [ ] **Step 7: Run the failure-path unit test to verify it passes**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.compact_surfacesStartupFailure --no-daemon`

Expected: PASS

- [ ] **Step 8: Commit the ViewModel compact fire-and-forget change**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt
git commit -m "refactor: make session compact fire-and-forget"
```

### Task 2: Drive compact menu state from synchronized session status

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: Write a failing unit test for compact state derived from session status**

Add a small pure-behavior test that captures the new menu logic in the ViewModel-facing layer. If no helper exists yet, introduce one in production code after the test fails.

```kotlin
@Test
fun compactActionState_isEnabledOnlyWhenSessionIsIdle() {
    assertThat(SessionCompactActionState.fromStatus(SessionStatus.IDLE.name).enabled).isTrue()
    assertThat(SessionCompactActionState.fromStatus(SessionStatus.IDLE.name).label).isEqualTo("compact")

    assertThat(SessionCompactActionState.fromStatus("compacting").enabled).isFalse()
    assertThat(SessionCompactActionState.fromStatus("compacting").label).isEqualTo("compacting")

    assertThat(SessionCompactActionState.fromStatus(SessionStatus.BUSY.name).enabled).isFalse()
    assertThat(SessionCompactActionState.fromStatus(SessionStatus.BUSY.name).label).isEqualTo("compact")
}
```

- [ ] **Step 2: Run the state unit test to verify it fails correctly**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.compactActionState_isEnabledOnlyWhenSessionIsIdle --no-daemon`

Expected: FAIL because the compact action state helper does not exist yet.

- [ ] **Step 3: Add the minimal production helper for compact action state**

Create a tiny internal helper in `SessionDetailViewModel.kt` or a focused sibling file in the same package:

```kotlin
internal data class SessionCompactActionState(
    val enabled: Boolean,
    val label: String,
) {
    companion object {
        fun fromStatus(status: String): SessionCompactActionState {
            return when (status.lowercase()) {
                "idle" -> SessionCompactActionState(enabled = true, label = "compact")
                "compacting" -> SessionCompactActionState(enabled = false, label = "compacting")
                else -> SessionCompactActionState(enabled = false, label = "compact")
            }
        }
    }
}
```

- [ ] **Step 4: Expose the current session status to the screen**

In `SessionDetailViewModel.kt`, add a public read-only flow:

```kotlin
val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()
```

- [ ] **Step 5: Update the screen to use synchronized status instead of `isCompacting`**

In `SessionDetailScreen.kt`, replace the compact menu state wiring:

```kotlin
val sessionStatus by viewModel.sessionStatus.collectAsState()
val compactActionState = remember(sessionStatus) {
    SessionCompactActionState.fromStatus(sessionStatus)
}
```

Then replace the menu item:

```kotlin
DropdownMenuItem(
    text = { Text(if (compactActionState.label == "compacting") stringResource(R.string.compacting) else stringResource(R.string.compact)) },
    onClick = {
        menuExpanded = false
        viewModel.compact(sessionID)
    },
    enabled = compactActionState.enabled,
)
```

Also remove the old `isCompacting` collection from the screen.

- [ ] **Step 6: Run the state unit test again to verify it passes**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.compactActionState_isEnabledOnlyWhenSessionIsIdle --no-daemon`

Expected: PASS

- [ ] **Step 7: Run focused unit tests covering the whole ViewModel file**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest --no-daemon`

Expected: PASS

- [ ] **Step 8: Commit the synced compact action state change**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt
git commit -m "refactor: drive compact action from session status"
```

### Task 3: Render compact summaries as Markdown

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt`
- Test: `android/feature/session/src/androidTest/java/com/openmate/feature/session/component/SessionMessageRendererCompactionTest.kt`

- [ ] **Step 1: Write a failing UI test for Markdown compact summary rendering**

Add this test to `SessionMessageRendererCompactionTest.kt`:

```kotlin
@Test
fun completedCompactionMessage_rendersMarkdownSummary() {
    val entity = SessionMessage(
        id = "compaction-markdown",
        sessionId = "session-1",
        type = "compaction",
        data = """
            {
              "reason": "auto",
              "summary": "# Compact Summary\n\n- item one\n- item two",
              "time": {
                "created": 1000,
                "completed": 61000
              }
            }
        """.trimIndent(),
        timeCreated = 1_000L,
        timeUpdated = 61_000L,
        completedAt = 61_000L,
    )

    composeRule.setContent {
        MaterialTheme {
            SessionMessageRenderer(
                entity = entity,
                onFullContentRequest = {},
            )
        }
    }

    composeRule.onNodeWithText("▸ compaction").performClick()
    composeRule.onNodeWithText("Compact Summary").assertIsDisplayed()
    composeRule.onNodeWithText("item one").assertIsDisplayed()
    composeRule.onNodeWithText("item two").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the compaction UI test class to verify the new test fails correctly**

Run: `./gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.openmate.feature.session.component.SessionMessageRendererCompactionTest" ":feature:session:connectedDebugAndroidTest" --no-daemon`

Expected: FAIL because compact summaries still use plain `Text` rendering.

- [ ] **Step 3: Replace compact summary `Text` with the existing Markdown component**

In `SessionMessageRenderer.kt`, import the same markdown component used elsewhere in the app and replace the summary block:

```kotlin
MarkdownText(
    markdown = summary,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
    style = MaterialTheme.typography.bodySmall.copy(
        color = MaterialTheme.colorScheme.onSurface,
    ),
    syntaxHighlightColor = CodeBlockBackground,
    syntaxHighlightTextColor = CodeBlockText,
    isTextSelectable = true,
)
```

If `CodeBlockBackground` and `CodeBlockText` are not visible in this file, define local equivalents rather than widening unrelated component visibility.

- [ ] **Step 4: Keep the no-nested-scroll behavior intact**

Confirm the compact summary block remains free of `verticalScroll()` and does not add any nested scrolling container back into the expanded content.

No code block needed if Step 3 already preserves this; just verify the final rendering block stays flat inside the `Card`.

- [ ] **Step 5: Run the compaction instrumentation test class to verify it passes**

Run: `./gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.openmate.feature.session.component.SessionMessageRendererCompactionTest" ":feature:session:connectedDebugAndroidTest" --no-daemon`

Expected: PASS

- [ ] **Step 6: Commit the Markdown compact summary change**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt android/feature/session/src/androidTest/java/com/openmate/feature/session/component/SessionMessageRendererCompactionTest.kt
git commit -m "feat: render compact summaries as markdown"
```

### Task 4: Final verification

**Files:**
- Modify: none
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- Test: `android/feature/session/src/androidTest/java/com/openmate/feature/session/component/SessionMessageRendererCompactionTest.kt`

- [ ] **Step 1: Run the full ViewModel unit test class**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest --no-daemon`

Expected: PASS

- [ ] **Step 2: Run the full compact renderer instrumentation test class**

Run: `./gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.openmate.feature.session.component.SessionMessageRendererCompactionTest" ":feature:session:connectedDebugAndroidTest" --no-daemon`

Expected: PASS

- [ ] **Step 3: Build the latest debug APK for manual verification**

Run: `./gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual verification on device**

Verify these behaviors after installing the rebuilt APK:

```text
1. In an idle session, the compact menu item is enabled and shows "compact".
2. After tapping compact, the app does not locally wait 2 seconds or show a false timeout failure.
3. While the server reports compacting, the compact menu item is disabled and shows "compacting".
4. When compact finishes, the summary can be expanded without crashing.
5. Expanded compact content renders Markdown structure such as headings and bullet lists.
```

- [ ] **Step 5: Commit the final verification-only touchups if any are needed**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt android/feature/session/src/androidTest/java/com/openmate/feature/session/component/SessionMessageRendererCompactionTest.kt
git commit -m "test: verify compact async and markdown behavior"
```
