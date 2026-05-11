# Session Windowed Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make session detail use a high-performance in-memory message window for hot-path sync updates, while loading older history from Room in 30-message pages.

**Architecture:** Keep Room as the persistence source of truth, but stop using `observeBySession()` to drive the session detail message list. Cold start and history paging read from Room; incremental sync returns concrete change ranges that the ViewModel applies directly to the current in-memory window.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, Flow, Google Truth, JUnit4

---

## File Map

### Existing files to modify

- `android/core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt`
  - Add composite index for `(sessionId, timeCreated)`.
- `android/core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`
  - Bump DB version and add migration for the new index.
- `android/core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt`
  - Add recent-window query, older-page query, and count helper queries.
- `android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt`
  - Change sync contract from `Unit` to explicit sync result and add window/page APIs.
- `android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`
  - Return sync change results, add cold-start/page DB reads, stop relying on Room observation for hot path.
- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
  - Replace DB-driven message Flow with in-memory window state and page loading.
- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
  - Trigger `loadOlderMessages()` when top is reached and scope search to current window.

### New files to create

- `android/core/domain/src/main/java/com/openmate/core/domain/model/SessionMessageSyncResult.kt`
  - Domain model for sync results and window-facing change events.
- `android/feature/session/src/main/java/com/openmate/feature/session/SessionMessageWindowManager.kt`
  - Pure reducer/helper that applies insert/update/remove/page changes to the current window.
- `android/feature/session/src/test/java/com/openmate/feature/session/SessionMessageWindowManagerTest.kt`
  - Unit tests for window update behavior.
- `android/core/database/src/test/java/com/openmate/core/database/dao/SessionMessageDaoPagingTest.kt`
  - Room query tests for recent-window and older-page queries.

### Existing tests to extend

- `android/feature/session/src/test/java/com/openmate/feature/session/SessionBusyTimerCalculatorTest.kt`
  - Keep as-is unless timer behavior regresses.

---

### Task 1: Add Room support for recent-window and older-page queries

**Files:**
- Modify: `android/core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt`
- Modify: `android/core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`
- Modify: `android/core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt`
- Create: `android/core/database/src/test/java/com/openmate/core/database/dao/SessionMessageDaoPagingTest.kt`

- [ ] **Step 1: Write the failing DAO paging test**

```kotlin
@Test
fun observeRecentWindow_returnsOnlyLatestMessagesAscending() = runTest {
    dao.upsertAll((1L..5L).map { t -> message(id = "m$t", timeCreated = t) })

    val result = dao.getRecentWindow(sessionId = SESSION_ID, limit = 3)

    assertThat(result.map { it.id }).containsExactly("m3", "m4", "m5").inOrder()
}

@Test
fun getOlderPage_returnsMessagesBeforeWindowStart() = runTest {
    dao.upsertAll((1L..6L).map { t -> message(id = "m$t", timeCreated = t) })

    val result = dao.getOlderPage(
        sessionId = SESSION_ID,
        beforeTimeCreated = 4L,
        beforeId = "m4",
        limit = 2,
    )

    assertThat(result.map { it.id }).containsExactly("m2", "m3").inOrder()
}
```

- [ ] **Step 2: Run DAO paging test to verify it fails**

Run: `./gradlew.bat :core:database:testDebugUnitTest --tests "com.openmate.core.database.dao.SessionMessageDaoPagingTest" --no-daemon`

Expected: FAIL because `getRecentWindow()` and `getOlderPage()` do not exist yet.

- [ ] **Step 3: Add composite index to `SessionMessageEntity`**

```kotlin
@Entity(
    tableName = "session_message",
    indices = [
        Index("sessionId"),
        Index("sessionId", "type"),
        Index("timeCreated"),
        Index(value = ["sessionId", "timeCreated"]),
    ],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val completedAt: Long? = null,
    val roundMark: Boolean = true,
)
```

- [ ] **Step 4: Add migration and DB version bump**

```kotlin
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_session_message_sessionId_timeCreated ON session_message(sessionId, timeCreated)",
        )
    }
}

@Database(
    entities = [
        SessionEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
        SyncStateEntity::class,
        TodoEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase()
```

- [ ] **Step 5: Add DAO APIs for recent window and older page**

```kotlin
@Query(
    """
    SELECT * FROM (
        SELECT * FROM session_message
        WHERE sessionId = :sessionId
        ORDER BY timeCreated DESC, id DESC
        LIMIT :limit
    ) ORDER BY timeCreated ASC, id ASC
    """,
)
suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessageEntity>

@Query(
    """
    SELECT * FROM (
        SELECT * FROM session_message
        WHERE sessionId = :sessionId
          AND (timeCreated < :beforeTimeCreated OR (timeCreated = :beforeTimeCreated AND id < :beforeId))
        ORDER BY timeCreated DESC, id DESC
        LIMIT :limit
    ) ORDER BY timeCreated ASC, id ASC
    """,
)
suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int): List<SessionMessageEntity>

@Query("SELECT COUNT(*) FROM session_message WHERE sessionId = :sessionId")
suspend fun countBySession(sessionId: String): Int
```

- [ ] **Step 6: Run DAO paging test to verify it passes**

Run: `./gradlew.bat :core:database:testDebugUnitTest --tests "com.openmate.core.database.dao.SessionMessageDaoPagingTest" --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt android/core/database/src/main/java/com/openmate/core/database/AppDatabase.kt android/core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt android/core/database/src/test/java/com/openmate/core/database/dao/SessionMessageDaoPagingTest.kt
git commit -m "feat: add paged session message queries"
```

### Task 2: Return explicit sync results from session message sync

**Files:**
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/SessionMessageSyncResult.kt`
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`

- [ ] **Step 1: Create a concrete repository contract test**

```kotlin
@Test
fun incrementalSync_returnsInsertedAndUpdatedMessages() = runTest {
    val result = repository.incrementalSync(SESSION_ID)

    assertThat(result.changes).isNotEmpty()
    assertThat(result.lastSeq).isGreaterThan(0L)
}
```

- [ ] **Step 2: Run the targeted repository test to verify it fails**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon`

Expected: FAIL because `incrementalSync()` returns `Unit` and no sync result model exists.

- [ ] **Step 3: Create domain sync result models**

```kotlin
data class SessionMessageSyncResult(
    val lastSeq: Long,
    val changes: List<SessionMessageSyncChange>,
)

sealed interface SessionMessageSyncChange {
    data class Insert(val message: SessionMessage) : SessionMessageSyncChange
    data class Update(val message: SessionMessage) : SessionMessageSyncChange
    data class Remove(val messageId: String) : SessionMessageSyncChange
}
```

- [ ] **Step 4: Update repository interface signatures**

```kotlin
interface SessionMessageRepository {
    suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage>
    suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int): List<SessionMessage>
    suspend fun initSync(sessionId: String, limit: Int = 30): SessionMessageSyncResult
    suspend fun incrementalSync(sessionId: String): SessionMessageSyncResult
    suspend fun fetchFullMessage(sessionId: String, messageId: String)
    suspend fun getLastSeq(sessionId: String): Long?
}
```

- [ ] **Step 5: Make repository implementation return concrete changes**

```kotlin
override suspend fun incrementalSync(sessionId: String): SessionMessageSyncResult {
    val db = dbProvider.getActive()
    val syncState = db.syncStateDao().get(sessionId) ?: return SessionMessageSyncResult(0L, emptyList())
    val response = syncApiClient.events(sessionId, syncState.lastSeq)
    val loader = EventReplayer.DbLoader { action ->
        when (action) {
            is EventReplayer.DbLoader.Action.LoadById -> db.sessionMessageDao().getById(action.id)
            is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant ->
                db.sessionMessageDao().getLatestIncompleteAssistant(action.sessionId)
        }
    }
    val replayEvents = response.events.map { e -> ReplayEvent(e.id, e.type, e.data) }
    val changes = EventReplayer().replay(replayEvents, sessionId, loader)

    db.withTransaction {
        for (change in coalesced.values) {
            when (change) {
                is ReplayChange.Insert -> db.sessionMessageDao().upsert(change.entity)
                is ReplayChange.Update -> {
                    val existing = db.sessionMessageDao().getById(change.id) ?: continue
                    db.sessionMessageDao().upsert(
                        existing.copy(
                            data = change.data.toString(),
                            timeUpdated = change.timeUpdated,
                            completedAt = change.completedAt ?: existing.completedAt,
                            roundMark = change.roundMark ?: existing.roundMark,
                        ),
                    )
                }
            }
        }
        db.syncStateDao().upsert(SyncStateEntity(sessionId, response.maxSeq ?: syncState.lastSeq))
    }

    return SessionMessageSyncResult(
        lastSeq = response.maxSeq ?: syncState.lastSeq,
        changes = coalesced.values.mapNotNull { change ->
            when (change) {
                is ReplayChange.Insert -> SessionMessageSyncChange.Insert(change.entity.toDomain())
                is ReplayChange.Update -> db.sessionMessageDao().getById(change.id)?.toDomain()?.let(SessionMessageSyncChange::Update)
            }
        },
    )
}
```

- [ ] **Step 6: Add recent window and older page repository methods**

```kotlin
override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> {
    return dbProvider.getActive().sessionMessageDao().getRecentWindow(sessionId, limit).map { it.toDomain() }
}

override suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int): List<SessionMessage> {
    return dbProvider.getActive().sessionMessageDao().getOlderPage(sessionId, beforeTimeCreated, beforeId, limit).map { it.toDomain() }
}
```

- [ ] **Step 7: Run repository tests to verify they pass**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/core/domain/src/main/java/com/openmate/core/domain/model/SessionMessageSyncResult.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt
git commit -m "feat: return session message sync changes"
```

### Task 3: Build the in-memory window reducer

**Files:**
- Create: `android/feature/session/src/main/java/com/openmate/feature/session/SessionMessageWindowManager.kt`
- Create: `android/feature/session/src/test/java/com/openmate/feature/session/SessionMessageWindowManagerTest.kt`

- [ ] **Step 1: Write failing reducer tests**

```kotlin
@Test
fun applyInsert_appendsNewTailMessage() {
    val state = SessionMessageWindowManager.State(messages = listOf(msg("m1", 1), msg("m2", 2)), loadedCount = 30, hasOlderMessages = true)

    val updated = SessionMessageWindowManager.apply(
        state,
        listOf(SessionMessageSyncChange.Insert(msg("m3", 3))),
    )

    assertThat(updated.messages.map { it.id }).containsExactly("m1", "m2", "m3").inOrder()
}

@Test
fun applyUpdate_replacesMessageInWindowById() {
    val state = SessionMessageWindowManager.State(messages = listOf(msg("m1", 1), msg("m2", 2, data = "old")), loadedCount = 30, hasOlderMessages = false)

    val updated = SessionMessageWindowManager.apply(
        state,
        listOf(SessionMessageSyncChange.Update(msg("m2", 2, data = "new"))),
    )

    assertThat(updated.messages.single { it.id == "m2" }.data).isEqualTo("new")
}
```

- [ ] **Step 2: Run reducer tests to verify they fail**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionMessageWindowManagerTest" --no-daemon`

Expected: FAIL because `SessionMessageWindowManager` does not exist.

- [ ] **Step 3: Write the minimal reducer**

```kotlin
object SessionMessageWindowManager {
    data class State(
        val messages: List<SessionMessage>,
        val loadedCount: Int,
        val hasOlderMessages: Boolean,
    )

    fun apply(state: State, changes: List<SessionMessageSyncChange>): State {
        var messages = state.messages
        for (change in changes) {
            messages = when (change) {
                is SessionMessageSyncChange.Insert -> (messages + change.message).distinctBy { it.id }.sortedWith(compareBy(SessionMessage::timeCreated, SessionMessage::id))
                is SessionMessageSyncChange.Update -> messages.map { if (it.id == change.message.id) change.message else it }
                is SessionMessageSyncChange.Remove -> messages.filterNot { it.id == change.messageId }
            }
        }
        return state.copy(messages = messages)
    }
}
```

- [ ] **Step 4: Add prepend helper for older-page loading**

```kotlin
fun prependOlderPage(state: State, olderPage: List<SessionMessage>, hasOlderMessages: Boolean): State {
    val merged = (olderPage + state.messages)
        .distinctBy { it.id }
        .sortedWith(compareBy(SessionMessage::timeCreated, SessionMessage::id))

    return state.copy(
        messages = merged,
        loadedCount = merged.size,
        hasOlderMessages = hasOlderMessages,
    )
}
```

- [ ] **Step 5: Run reducer tests to verify they pass**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionMessageWindowManagerTest" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionMessageWindowManager.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionMessageWindowManagerTest.kt
git commit -m "feat: add session message window reducer"
```

### Task 4: Switch SessionDetailViewModel to in-memory message windows

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt`

- [ ] **Step 1: Write failing ViewModel window behavior tests**

```kotlin
@Test
fun loadSession_initializesRecentThirtyMessagesOnly() = runTest {
    val fakeRepo = FakeSessionMessageRepository(
        recentWindow = (1..30).map { msg("m$it", it.toLong()) },
        initResult = SessionMessageSyncResult(30L, emptyList()),
    )
    val vm = SessionDetailViewModel(
        appContext = application,
        sessionRepository = FakeSessionRepository(),
        sessionMessageRepository = fakeRepo,
        todoRepository = FakeTodoRepository(),
        questionRepository = FakeQuestionRepository(),
        permissionRepository = FakePermissionRepository(),
        sseEventRepository = FakeSseEventRepository(),
        apiClient = FakeOpencodeApiClient(),
    )

    vm.loadSession("session")
    advanceUntilIdle()

    assertThat(vm.messages.value).hasSize(30)
    assertThat(vm.messages.value.first().id).isEqualTo("m1")
}

@Test
fun sendMessage_incrementalSyncAppliesReturnedInsertWithoutReloadingWindow() = runTest {
    val fakeRepo = FakeSessionMessageRepository(
        recentWindow = (1..30).map { msg("m$it", it.toLong()) },
        initResult = SessionMessageSyncResult(30L, emptyList()),
        incrementalResult = SessionMessageSyncResult(31L, listOf(SessionMessageSyncChange.Insert(msg("m31", 31L)))),
    )
    val vm = SessionDetailViewModel(
        appContext = application,
        sessionRepository = FakeSessionRepository(),
        sessionMessageRepository = fakeRepo,
        todoRepository = FakeTodoRepository(),
        questionRepository = FakeQuestionRepository(),
        permissionRepository = FakePermissionRepository(),
        sseEventRepository = FakeSseEventRepository(),
        apiClient = FakeOpencodeApiClient(),
    )

    vm.sendMessage("session")
    advanceUntilIdle()

    assertThat(vm.messages.value.last().id).isEqualTo("m31")
}
```

- [ ] **Step 2: Run ViewModel tests to verify they fail**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionDetailViewModelTest" --no-daemon`

Expected: FAIL because ViewModel still relies on `observeMessages()` and no window-based initialization exists.

- [ ] **Step 3: Replace DB observation with in-memory message state**

```kotlin
private val _messages = MutableStateFlow<List<SessionMessage>>(emptyList())
private var messageWindowState = SessionMessageWindowManager.State(
    messages = emptyList(),
    loadedCount = 30,
    hasOlderMessages = false,
)

private suspend fun rebuildInitialWindow(sessionId: String) {
    val recent = sessionMessageRepository.getRecentWindow(sessionId, limit = 30)
    messageWindowState = messageWindowState.copy(
        messages = recent,
        loadedCount = recent.size,
        hasOlderMessages = recent.isNotEmpty(),
    )
    _messages.value = recent
}
```

- [ ] **Step 4: Apply sync changes directly after initSync/incrementalSync**

```kotlin
private fun applySyncResult(result: SessionMessageSyncResult) {
    messageWindowState = SessionMessageWindowManager.apply(messageWindowState, result.changes)
    _messages.value = messageWindowState.messages
    recalculateMessageDerivedState(messageWindowState.messages)
}
```

- [ ] **Step 5: Add `loadOlderMessages()` to prepend older page**

```kotlin
fun loadOlderMessages() {
    val first = _messages.value.firstOrNull() ?: return
    val sessionId = currentSessionID ?: return
    viewModelScope.launch(Dispatchers.IO) {
        val older = sessionMessageRepository.getOlderPage(sessionId, first.timeCreated, first.id, 30)
        if (older.isNotEmpty()) {
            messageWindowState = SessionMessageWindowManager.prependOlderPage(
                state = messageWindowState,
                olderPage = older,
                hasOlderMessages = older.size == 30,
            )
            _messages.value = messageWindowState.messages
        }
    }
}
```

- [ ] **Step 6: Move message-derived state into a helper that works on the current window list**

```kotlin
private fun recalculateMessageDerivedState(list: List<SessionMessage>) {
    val lastMsg = list.lastOrNull()
    val hasBusyAssistant = lastMsg != null && !(lastMsg.type == "assistant" && lastMsg.roundMark)
    val lastAssistant = list.lastOrNull { it.type == "assistant" }
    val lastAssistantFinish = lastAssistant?.let {
        runCatching {
            Json.parseToJsonElement(it.data).jsonObject["finish"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
    _isStreaming.value = lastAssistant?.completedAt == null || (lastAssistantFinish != "stop" && lastAssistantFinish != "length")
    _queuedMessageIds.value = buildQueuedMessageIds(list)
    if (hasBusyAssistant && _currentBusyStart.value == null) {
        _currentBusyStart.value = SessionBusyTimerCalculator.findBusyStart(list)
    }
    if (!hasBusyAssistant) {
        _currentBusyStart.value = null
    }
}
```

- [ ] **Step 7: Run ViewModel tests to verify they pass**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionDetailViewModelTest" --no-daemon`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt
git commit -m "feat: drive session detail from message window state"
```

### Task 5: Add top-of-list paging and scope search to current window

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageSearchPanel.kt`

- [ ] **Step 1: Write failing UI behavior test sketch**

```kotlin
@Test
fun reachingTop_requestsOlderMessagesOnce() {
    // compose test: scroll to first item, assert loadOlderMessages called once
}

@Test
fun searchPanel_filtersOnlyCurrentWindowMessages() {
    val messages = listOf(msg("loaded", 1, data = "needle"))
    val results = messages.filter { it.data.contains("needle") }
    assertThat(results.map { it.id }).containsExactly("loaded")
}
```

- [ ] **Step 2: Run UI/search tests to verify they fail**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon`

Expected: FAIL because top-triggered paging and current-window-only search behavior are not wired yet.

- [ ] **Step 3: Trigger `loadOlderMessages()` when top is reached**

```kotlin
LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, messages.firstOrNull()?.id) {
    if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
        viewModel.loadOlderMessages()
    }
}
```

- [ ] **Step 4: Preserve scroll position after prepend**

```kotlin
val previousFirstId = remember { mutableStateOf<String?>(null) }
LaunchedEffect(messages.firstOrNull()?.id) {
    val anchor = previousFirstId.value ?: return@LaunchedEffect
    val newIndex = messages.indexOfFirst { it.id == anchor }
    if (newIndex > 0) listState.scrollToItem(newIndex)
    previousFirstId.value = messages.firstOrNull()?.id
}
```

- [ ] **Step 5: Scope search panel to the currently loaded `messages` list only**

```kotlin
SessionMessageSearchPanel(
    messages = messages,
    onDismiss = { showSearch = false },
    onNavigateToMessage = { message ->
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            coroutineScope.launch { listState.animateScrollToItem(index) }
        }
    },
)
```

- [ ] **Step 6: Run UI/search tests to verify they pass**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageSearchPanel.kt
git commit -m "feat: page older session messages from top"
```

### Task 6: Full verification and cleanup

**Files:**
- Modify: any touched files from Tasks 1-5 only if required by failing verification

- [ ] **Step 1: Run database tests**

Run: `./gradlew.bat :core:database:testDebugUnitTest --no-daemon`

Expected: PASS.

- [ ] **Step 2: Run data tests**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon`

Expected: PASS.

- [ ] **Step 3: Run session feature tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon`

Expected: PASS.

- [ ] **Step 4: Compile session feature**

Run: `./gradlew.bat --stop && ./gradlew.bat :feature:session:compileDebugKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Build debug APK**

Run: `./gradlew.bat --stop && ./gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Manual verification checklist**

```text
1. Open a large session: only latest 30 messages should render.
2. Scroll to top once: 30 older messages should prepend without jump.
3. Send a message in a large session: new message should appear quickly without full-history pause.
4. Let assistant stream: current window should update in place.
5. Open search: results should only come from the loaded window.
6. Leave and re-enter the session: recent 30 messages should rebuild correctly from DB.
```

- [ ] **Step 7: Final commit**

```bash
git add android/core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt android/core/database/src/main/java/com/openmate/core/database/AppDatabase.kt android/core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt android/core/database/src/test/java/com/openmate/core/database/dao/SessionMessageDaoPagingTest.kt android/core/domain/src/main/java/com/openmate/core/domain/model/SessionMessageSyncResult.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionMessageWindowManager.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionMessageWindowManagerTest.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageSearchPanel.kt
git commit -m "feat: incrementally update session message windows"
```
