# 会话同步日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android 会话详情页增加“同步日志”调试工具，支持查看全局同步日志、当前会话高亮、正则过滤、文本选择复制、清除日志、重连 SSE 和手动发起当前会话增量同步。

**Architecture:** 在 `core:data` 新增一个全局内存同步日志组件，统一采集 `SyncSseClient`、`SyncSseHandler`、`SessionMessageRepositoryImpl` 与手动调试动作的结构化日志，再格式化成最终文本供 UI 展示与正则过滤。会话详情通过独立日志页面消费该日志流，并通过一个专用的调试控制器执行重连 SSE、清空日志和手动增量同步。

**Tech Stack:** Kotlin, Coroutines Flow, Hilt, Compose Material3, Robolectric, Google Truth

---

### 文件结构

**新增文件：**

- `android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogEntry.kt`
  责任：定义同步日志结构化模型、枚举与最终文本格式化入口。
- `android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogStore.kt`
  责任：维护最多 200 条的全局内存环形日志缓冲区，并以 `StateFlow` 对外暴露。
- `android/core/data/src/main/java/com/openmate/core/data/sync/SyncDebugController.kt`
  责任：封装调试动作，包括记录手动动作、清空日志、重连 SSE、手动触发会话增量同步。
- `android/feature/session/src/main/java/com/openmate/feature/session/SyncLogViewState.kt`
  责任：承载日志页显示所需的纯 UI 状态与过滤结果。
- `android/feature/session/src/main/java/com/openmate/feature/session/SyncLogScreen.kt`
  责任：实现同步日志独立页面 UI。

**修改文件：**

- `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
  责任：记录 SSE 生命周期、通知接收与相关异常日志。
- `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
  责任：记录通知排队、debounce、活动同步跳过与自动同步执行日志。
- `android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`
  责任：记录增量同步开始、包大小、单条 event 大小、replay 汇总、结束与失败日志。
- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
  责任：暴露日志页面状态、提供日志页操作方法，并继续承担当前会话上下文。
- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
  责任：会话详情菜单新增“同步日志”入口并接入日志页。

**测试文件：**

- `android/core/data/src/test/java/com/openmate/core/data/sync/SyncLogStoreTest.kt`
- `android/core/data/src/test/java/com/openmate/core/data/sync/SyncDebugControllerTest.kt`
- `android/core/data/src/test/java/com/openmate/core/data/repository/SessionMessageRepositoryImplTest.kt`
- `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailScreenLogicTest.kt`

---

### Task 1: 建立同步日志核心模型与内存存储

**Files:**
- Create: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogEntry.kt`
- Create: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogStore.kt`
- Create: `android/core/data/src/test/java/com/openmate/core/data/sync/SyncLogStoreTest.kt`

- [ ] **Step 1: 写失败测试，证明日志存储只保留最新 200 条并暴露最终文本**

```kotlin
@Test
fun append_keepsOnlyLatest200Entries_andFormatsFinalText() {
    val store = SyncLogStore()

    repeat(205) { index ->
        store.append(
            SyncLogEntry(
                id = index.toLong(),
                timestamp = 1_700_000_000_000L + index,
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                sessionId = if (index % 2 == 0) "ses_current" else null,
                title = "增量消息处理",
                message = "seq=${1000 + index}",
                bytes = 128 + index,
                relatedSeq = (1000 + index).toLong(),
                traceId = "inc-1",
            )
        )
    }

    val entries = store.entries.value

    assertThat(entries).hasSize(200)
    assertThat(entries.first().id).isEqualTo(5L)
    assertThat(entries.last().renderedText).contains("INFO [Sync] 增量消息处理")
    assertThat(entries.last().renderedText).contains("trace=inc-1")
    assertThat(entries.last().renderedText).contains("bytes=")
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sync.SyncLogStoreTest.append_keepsOnlyLatest200Entries_andFormatsFinalText"`

Expected: FAIL，因为 `SyncLogStore` 与 `SyncLogEntry` 尚不存在。

- [ ] **Step 3: 写最小日志模型与最终文本格式化代码**

```kotlin
enum class SyncLogLevel { Info, Warn, Error }

enum class SyncLogCategory { Sse, Sync, Manual, Poll }

data class SyncLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: SyncLogLevel,
    val category: SyncLogCategory,
    val sessionId: String?,
    val title: String,
    val message: String,
    val bytes: Int?,
    val relatedSeq: Long?,
    val traceId: String?,
) {
    val renderedText: String
        get() {
            val timeText = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(timestamp))
            val parts = mutableListOf(
                timeText,
                level.name.uppercase(),
                "[${category.name}]",
                title,
            )
            sessionId?.let { parts += "session=$it" }
            traceId?.let { parts += "trace=$it" }
            relatedSeq?.let { parts += "seq=$it" }
            bytes?.let { parts += "bytes=$it" }
            parts += "message=$message"
            return parts.joinToString(" ")
        }
}
```

- [ ] **Step 4: 写最小内存环形日志存储实现**

```kotlin
@Singleton
class SyncLogStore @Inject constructor() {
    private val _entries = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val entries: StateFlow<List<SyncLogEntry>> = _entries.asStateFlow()

    fun append(entry: SyncLogEntry) {
        _entries.value = (_entries.value + entry).takeLast(200)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
```

- [ ] **Step 5: 运行单测并确认通过**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sync.SyncLogStoreTest.append_keepsOnlyLatest200Entries_andFormatsFinalText"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogEntry.kt android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogStore.kt android/core/data/src/test/java/com/openmate/core/data/sync/SyncLogStoreTest.kt
git commit -m "feat: add in-memory sync log store"
```

### Task 2: 增加统一日志写入接口与正则过滤辅助逻辑

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogStore.kt`
- Create: `android/feature/session/src/main/java/com/openmate/feature/session/SyncLogViewState.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailScreenLogicTest.kt`

- [ ] **Step 1: 写失败测试，证明正则过滤发生在最终文本层，非法正则保持当前结果不变**

```kotlin
@Test
fun filterLogs_usesRenderedTextRegex_andKeepsPreviousResultsOnInvalidPattern() {
    val source = listOf(
        "15:42:18.892 INFO [Sync] 增量包返回 session=ses_a trace=inc-1 bytes=18234 message=events response received",
        "15:48:12.312 ERROR [Sync] 增量同步失败 session=ses_b trace=inc-2 message=SocketTimeoutException",
    )

    val matched = filterRenderedLogs(
        renderedLogs = source,
        query = "socket.*exception",
        previousVisibleLogs = source,
    )
    assertThat(matched).containsExactly(source[1])

    val invalid = filterRenderedLogs(
        renderedLogs = source,
        query = "[abc",
        previousVisibleLogs = matched,
    )
    assertThat(invalid.visibleLogs).containsExactly(source[1])
    assertThat(invalid.regexError).isNotNull()
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailScreenLogicTest.filterLogs_usesRenderedTextRegex_andKeepsPreviousResultsOnInvalidPattern"`

Expected: FAIL，因为过滤辅助逻辑尚不存在。

- [ ] **Step 3: 在日志存储中加入统一写入 helper，避免各调用方手拼 entry**

```kotlin
fun log(
    level: SyncLogLevel,
    category: SyncLogCategory,
    sessionId: String? = null,
    title: String,
    message: String,
    bytes: Int? = null,
    relatedSeq: Long? = null,
    traceId: String? = null,
) {
    append(
        SyncLogEntry(
            id = nextId.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            sessionId = sessionId,
            title = title,
            message = message,
            bytes = bytes,
            relatedSeq = relatedSeq,
            traceId = traceId,
        )
    )
}
```

- [ ] **Step 4: 实现日志页过滤状态与正则匹配辅助代码**

```kotlin
data class SyncLogFilterResult(
    val visibleLogs: List<String>,
    val regexError: String?,
)

fun filterRenderedLogs(
    renderedLogs: List<String>,
    query: String,
    previousVisibleLogs: List<String>,
): SyncLogFilterResult {
    if (query.isBlank()) return SyncLogFilterResult(renderedLogs, null)
    return try {
        val regex = Regex(query, setOf(RegexOption.IGNORE_CASE))
        SyncLogFilterResult(renderedLogs.filter { regex.containsMatchIn(it) }, null)
    } catch (_: IllegalArgumentException) {
        SyncLogFilterResult(previousVisibleLogs, "正则无效")
    }
}
```

- [ ] **Step 5: 运行单测并确认通过**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailScreenLogicTest.filterLogs_usesRenderedTextRegex_andKeepsPreviousResultsOnInvalidPattern"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/sync/SyncLogStore.kt android/feature/session/src/main/java/com/openmate/feature/session/SyncLogViewState.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailScreenLogicTest.kt
git commit -m "feat: add sync log filtering helpers"
```

### Task 3: 为同步链路增加 SSE 与 Handler 埋点

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
- Create: `android/core/data/src/test/java/com/openmate/core/data/sync/SyncDebugControllerTest.kt`

- [ ] **Step 1: 写失败测试，证明 SSE 生命周期与通知行为会写入日志**

```kotlin
@Test
fun reconnect_logsManualAndSseLifecycleEvents() = runTest {
    val logStore = SyncLogStore()
    val controller = createController(logStore = logStore)

    controller.reconnectSse()

    val rendered = logStore.entries.value.map { it.renderedText }
    assertThat(rendered.any { it.contains("用户请求重连SSE") }).isTrue()
    assertThat(rendered.any { it.contains("主动断开SSE") }).isTrue()
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sync.SyncDebugControllerTest.reconnect_logsManualAndSseLifecycleEvents"`

Expected: FAIL，因为控制器与日志接入尚未实现。

- [ ] **Step 3: 在 SyncSseClient 中加入最小日志埋点**

```kotlin
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sse, title = "发起SSE连接", message = "connecting to /api/bridge/sync/events token=${token != null}", traceId = traceId)
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sse, title = "SSE连接成功", message = "connected to sync event stream cost=${costMs}ms", traceId = traceId)
logStore.log(SyncLogLevel.Warn, SyncLogCategory.Sse, title = "SSE断开", message = "stream closed unexpectedly reconnectIn=3000ms", traceId = traceId)
logStore.log(SyncLogLevel.Error, SyncLogCategory.Sse, title = "SSE连接失败", message = "${e.javaClass.simpleName}: ${e.message}", traceId = traceId)
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sse, sessionId = sessionId, title = "收到同步通知", message = "session sync notification received", relatedSeq = seq, traceId = notifyTrace)
```

- [ ] **Step 4: 在 SyncSseHandler 中加入通知排队与 debounce 埋点**

```kotlin
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sse, sessionId = notification.sessionId, title = "同步通知入队", message = "queued for debounce window=500ms", relatedSeq = notification.seq, traceId = notifyTrace)
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sync, sessionId = sessionId, title = "准备发起增量同步", message = "debounce elapsed starting incremental sync trigger=sse", traceId = syncTrace)
logStore.log(SyncLogLevel.Warn, SyncLogCategory.Sync, sessionId = sessionId, title = "跳过增量同步", message = "sync already active for session", traceId = syncTrace)
logStore.log(SyncLogLevel.Error, SyncLogCategory.Sync, sessionId = sessionId, title = "自动增量同步失败", message = "${e.javaClass.simpleName}: ${e.message}", traceId = syncTrace)
```

- [ ] **Step 5: 运行相关单测并确认通过**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sync.SyncDebugControllerTest.reconnect_logsManualAndSseLifecycleEvents"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt android/core/data/src/test/java/com/openmate/core/data/sync/SyncDebugControllerTest.kt
git commit -m "feat: log sync sse lifecycle events"
```

### Task 4: 为增量同步仓库增加包大小、单条消息大小与汇总埋点

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`
- Modify: `android/core/data/src/test/java/com/openmate/core/data/repository/SessionMessageRepositoryImplTest.kt`

- [ ] **Step 1: 写失败测试，证明增量同步会记录包大小与单条事件大小**

```kotlin
@Test
fun incrementalSync_logsPackageBytesAndPerEventBytes() = runTest {
    val logStore = SyncLogStore()
    val repository = createRepository(logStore = logStore, eventsResponseBody = """
        {"events":[{"id":"e1","aggregateId":"$SESSION_ID","seq":11,"type":"message.updated.1","data":{"x":"hello"}}],"maxSeq":11}
    """.trimIndent())

    repository.initSync(SESSION_ID, 30)
    repository.incrementalSync(SESSION_ID)

    val rendered = logStore.entries.value.map { it.renderedText }
    assertThat(rendered.any { it.contains("增量包返回") && it.contains("bytes=") }).isTrue()
    assertThat(rendered.any { it.contains("增量消息处理") && it.contains("seq=11") && it.contains("bytes=") }).isTrue()
    assertThat(rendered.any { it.contains("增量同步结束") && it.contains("totalBytes=") }).isTrue()
}
```

- [ ] **Step 2: 运行仓库单测并确认失败**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.repository.SessionMessageRepositoryImplTest.incrementalSync_logsPackageBytesAndPerEventBytes"`

Expected: FAIL，因为仓库目前没有相关日志埋点。

- [ ] **Step 3: 在增量同步开始与响应阶段增加最小日志埋点**

```kotlin
val traceId = "inc-${System.nanoTime()}"
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sync, sessionId = sessionId, title = "增量同步开始", message = "incremental sync begin afterSeq=${syncState.lastSeq}", relatedSeq = syncState.lastSeq, traceId = traceId)

val packageBytes = responseRawBody.toByteArray(Charsets.UTF_8).size
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sync, sessionId = sessionId, title = "增量包返回", message = "events response received eventCount=${response.events.size} maxSeq=${response.maxSeq}", bytes = packageBytes, traceId = traceId)
```

- [ ] **Step 4: 在逐条事件处理与结束阶段增加最小日志埋点**

```kotlin
for (event in response.events) {
    val eventBytes = Json.encodeToString(event).toByteArray(Charsets.UTF_8).size
    logStore.log(
        SyncLogLevel.Info,
        SyncLogCategory.Sync,
        sessionId = sessionId,
        title = "增量消息处理",
        message = "event type=${event.type} aggregateId=${event.aggregateId}",
        bytes = eventBytes,
        relatedSeq = event.seq,
        traceId = traceId,
    )
}

logStore.log(SyncLogLevel.Info, SyncLogCategory.Sync, sessionId = sessionId, title = "Replay结果", message = "replay finished eventCount=${events.size} changeCount=${changes.size} coalescedWrites=${coalesced.size}", traceId = traceId)
logStore.log(SyncLogLevel.Info, SyncLogCategory.Sync, sessionId = sessionId, title = "增量同步结束", message = "incremental sync completed applied=${appliedChanges.size} cost=${totalMs}ms totalBytes=$packageBytes", bytes = packageBytes, relatedSeq = response.maxSeq, traceId = traceId)
```

- [ ] **Step 5: 在失败路径补充错误日志**

```kotlin
logStore.log(SyncLogLevel.Error, SyncLogCategory.Sync, sessionId = sessionId, title = "增量同步失败", message = "incremental sync failed afterSeq=${syncState.lastSeq} error=${e.javaClass.simpleName}: ${e.message} cost=${totalMs}ms", traceId = traceId)
```

- [ ] **Step 6: 运行仓库单测并确认通过**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.repository.SessionMessageRepositoryImplTest.incrementalSync_logsPackageBytesAndPerEventBytes"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt android/core/data/src/test/java/com/openmate/core/data/repository/SessionMessageRepositoryImplTest.kt
git commit -m "feat: log incremental sync traffic details"
```

### Task 5: 增加同步调试控制器并接入 ViewModel

**Files:**
- Create: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncDebugController.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: 写失败测试，证明 ViewModel 可以暴露日志列表并支持重连/手动同步/清空日志**

```kotlin
@Test
fun syncLogActions_exposeLogsAndInvokeController() = runTest(dispatcher) {
    val controller = FakeSyncDebugController()
    val viewModel = createViewModel(syncDebugController = controller)

    controller.emitLogs(
        listOf(
            fakeLog("12:00:00.000 INFO [Sse] SSE连接成功 trace=sse-1 message=connected"),
            fakeLog("12:00:01.000 INFO [Sync] 增量同步结束 session=$SESSION_ID trace=inc-1 message=done"),
        )
    )

    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.syncLogLines.value.isNotEmpty() }

    assertThat(viewModel.syncLogLines.value).hasSize(2)

    viewModel.reconnectSyncSse()
    viewModel.triggerManualIncrementalSync()
    viewModel.clearSyncLogs()

    assertThat(controller.reconnectCalls).isEqualTo(1)
    assertThat(controller.manualSyncCalls).containsExactly(SESSION_ID)
    assertThat(controller.clearCalls).isEqualTo(1)
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.syncLogActions_exposeLogsAndInvokeController"`

Expected: FAIL，因为 ViewModel 还没有日志状态与调试控制器依赖。

- [ ] **Step 3: 实现最小调试控制器**

```kotlin
@Singleton
class SyncDebugController @Inject constructor(
    private val logStore: SyncLogStore,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    private val apiClient: OpencodeApiClient,
    private val sessionMessageRepository: SessionMessageRepository,
) {
    val logs: StateFlow<List<SyncLogEntry>> = logStore.entries

    fun clearLogs() {
        logStore.clear()
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Manual, title = "日志已清除", message = "sync logs cleared by user")
    }

    fun reconnectSse() {
        val traceId = "sse-manual-${System.nanoTime()}"
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Manual, title = "用户请求重连SSE", message = "reconnect sync sse requested from sync log screen", traceId = traceId)
        syncSseClient.disconnect()
        syncSseHandler.start()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            syncSseClient.connect(apiClient.baseUrl)
        }
    }

    suspend fun triggerManualIncrementalSync(sessionId: String) {
        val traceId = "inc-${System.nanoTime()}"
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Manual, sessionId = sessionId, title = "用户发起增量同步", message = "manual incremental sync requested", traceId = traceId)
        sessionMessageRepository.incrementalSync(sessionId)
    }
}
```

- [ ] **Step 4: 在 ViewModel 中接入日志状态与操作方法**

```kotlin
private val _syncLogLines = MutableStateFlow<List<String>>(emptyList())
val syncLogLines: StateFlow<List<String>> = _syncLogLines.asStateFlow()

private val _syncLogEntries = MutableStateFlow<List<SyncLogEntry>>(emptyList())
val syncLogEntries: StateFlow<List<SyncLogEntry>> = _syncLogEntries.asStateFlow()

private fun observeSyncLogs() {
    viewModelScope.launch {
        syncDebugController.logs.collect { entries ->
            _syncLogEntries.value = entries
            _syncLogLines.value = entries.map { it.renderedText }
        }
    }
}

fun reconnectSyncSse() {
    syncDebugController.reconnectSse()
}

fun triggerManualIncrementalSync() {
    val sid = currentSessionID ?: return
    viewModelScope.launch(Dispatchers.IO) {
        syncDebugController.triggerManualIncrementalSync(sid)
    }
}

fun clearSyncLogs() {
    syncDebugController.clearLogs()
}
```

- [ ] **Step 5: 运行 ViewModel 单测并确认通过**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.syncLogActions_exposeLogsAndInvokeController"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/sync/SyncDebugController.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt
git commit -m "feat: add sync debug controller for session logs"
```

### Task 6: 实现同步日志页面与会话详情入口

**Files:**
- Create: `android/feature/session/src/main/java/com/openmate/feature/session/SyncLogScreen.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailScreenLogicTest.kt`

- [ ] **Step 1: 写失败测试，证明菜单新增同步日志入口且搜索过滤使用正则**

```kotlin
@Test
fun syncLogMenuAndRegexFilterBehaviors() {
    assertThat(sessionDetailMenuItems(includeCompact = false)).contains("同步日志")

    val visible = filterRenderedLogs(
        renderedLogs = listOf(
            "12:00:00.000 INFO [Sse] SSE连接成功 trace=sse-1 message=connected",
            "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
        ),
        query = "socket.*exception",
        previousVisibleLogs = emptyList(),
    )

    assertThat(visible.visibleLogs).containsExactly(
        "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
    )
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailScreenLogicTest.syncLogMenuAndRegexFilterBehaviors"`

Expected: FAIL，因为菜单项辅助逻辑和日志页入口尚未实现。

- [ ] **Step 3: 在会话详情菜单加入同步日志入口与打开状态**

```kotlin
var showSyncLogs by remember { mutableStateOf(false) }

DropdownMenuItem(
    text = { Text(stringResource(R.string.sync_logs)) },
    onClick = {
        menuExpanded = false
        showSyncLogs = true
    },
)

if (showSyncLogs) {
    SyncLogScreen(
        currentSessionId = sessionID,
        currentSessionTitle = sessionTitle,
        logEntries = syncLogEntries,
        onBack = { showSyncLogs = false },
        onCopy = { viewModel.copyVisibleSyncLogsToClipboard(it) },
        onClear = viewModel::clearSyncLogs,
        onReconnectSse = viewModel::reconnectSyncSse,
        onManualIncrementalSync = viewModel::triggerManualIncrementalSync,
    )
    return
}
```

- [ ] **Step 4: 实现最小日志页 UI，包含选择、正则过滤、自动跟随与按钮**

```kotlin
@Composable
fun SyncLogScreen(
    currentSessionId: String,
    currentSessionTitle: String,
    logEntries: List<SyncLogEntry>,
    onBack: () -> Unit,
    onCopy: (List<String>) -> Unit,
    onClear: () -> Unit,
    onReconnectSse: () -> Unit,
    onManualIncrementalSync: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var visibleLogs by remember { mutableStateOf(logEntries.map { it.renderedText }) }
    val filterResult = remember(logEntries, query) {
        filterRenderedLogs(logEntries.map { it.renderedText }, query, visibleLogs)
    }
    visibleLogs = filterResult.visibleLogs

    SelectionContainer {
        LazyColumn {
            items(logEntries.zip(visibleLogs)) { (entry, text) ->
                Text(
                    text = text,
                    color = when {
                        entry.level == SyncLogLevel.Error -> MaterialTheme.colorScheme.error
                        entry.sessionId == currentSessionId -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 5: 运行日志页相关单测并确认通过**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailScreenLogicTest.syncLogMenuAndRegexFilterBehaviors"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SyncLogScreen.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailScreenLogicTest.kt
git commit -m "feat: add sync log screen to session detail"
```

### Task 7: 补齐复制、清除、自动跟随与字符串资源

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SyncLogScreen.kt`
- Modify: `android/feature/session/src/main/res/values/strings.xml`
- Modify: `android/feature/session/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 写失败测试，证明复制只复制当前过滤结果**

```kotlin
@Test
fun copyVisibleSyncLogs_joinsOnlyFilteredVisibleRows() {
    val copied = mutableListOf<String>()
    val viewModel = createViewModel(onCopyLogs = { copied += it })

    viewModel.copyVisibleSyncLogsToClipboard(
        listOf(
            "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
        )
    )

    assertThat(copied.single()).isEqualTo(
        "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException"
    )
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.copyVisibleSyncLogs_joinsOnlyFilteredVisibleRows"`

Expected: FAIL，因为复制实现尚不存在。

- [ ] **Step 3: 在 ViewModel 中实现复制当前可见日志文本**

```kotlin
fun copyVisibleSyncLogsToClipboard(lines: List<String>) {
    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val text = lines.joinToString("\n")
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sync_logs", text))
}
```

- [ ] **Step 4: 在日志页补齐自动跟随与清除确认的最小交互**

```kotlin
var autoFollow by remember { mutableStateOf(true) }
var showClearConfirm by remember { mutableStateOf(false) }

if (showClearConfirm) {
    AlertDialog(
        onDismissRequest = { showClearConfirm = false },
        confirmButton = {
            TextButton(onClick = {
                showClearConfirm = false
                onClear()
            }) { Text(stringResource(R.string.clear)) }
        },
        dismissButton = {
            TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
        },
        text = { Text(stringResource(R.string.sync_logs_clear_confirm)) },
    )
}
```

- [ ] **Step 5: 增加最小字符串资源**

```xml
<string name="sync_logs">同步日志</string>
<string name="sync_logs_search_hint">输入正则过滤日志</string>
<string name="sync_logs_copy">复制日志</string>
<string name="sync_logs_reconnect_sse">重连SSE</string>
<string name="sync_logs_incremental_sync">增量同步</string>
<string name="sync_logs_clear_confirm">确认清除当前内存中的同步日志？</string>
<string name="sync_logs_follow_latest">自动滚动到最新</string>
<string name="sync_logs_invalid_regex">正则无效</string>
```

- [ ] **Step 6: 运行相关单测并确认通过**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.copyVisibleSyncLogs_joinsOnlyFilteredVisibleRows"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SyncLogScreen.kt android/feature/session/src/main/res/values/strings.xml android/feature/session/src/main/res/values-zh/strings.xml
git commit -m "feat: finish sync log actions and resources"
```

### Task 8: 全量验证与文档收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-session-sync-log-design.md`（仅当实现偏离规格时）

- [ ] **Step 1: 运行核心数据层单测**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sync.SyncLogStoreTest" --tests "com.openmate.core.data.sync.SyncDebugControllerTest" --tests "com.openmate.core.data.repository.SessionMessageRepositoryImplTest"`

Expected: PASS

- [ ] **Step 2: 运行会话功能单测**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest" --tests "com.openmate.feature.session.SessionDetailScreenLogicTest"`

Expected: PASS

- [ ] **Step 3: 手动检查以下行为**

Run app manually and verify:

- 会话详情菜单里出现 `同步日志`
- 打开后默认滚到最新日志
- 当前会话相关日志有高亮
- 输入合法正则后只显示匹配文本的日志
- 输入非法正则后保留当前列表并显示错误提示
- 日志文本可以长按选择复制
- `复制日志` 只复制当前过滤结果
- `清除日志` 会弹确认框并清空日志
- `重连SSE` 会产生一组 Manual + Sse 日志
- `增量同步` 会产生一组 Manual + Sync 日志

- [ ] **Step 4: 如实现与规格有轻微偏差，更新规格文档**

```markdown
仅在实际实现与规格存在明确偏差时，回写 `docs/superpowers/specs/2026-05-12-session-sync-log-design.md`，保持规格与代码一致。
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-05-12-session-sync-log-design.md
git commit -m "docs: align sync log spec with implementation"
```

---

### 自检结论

- 规格覆盖：已覆盖菜单入口、独立日志页、200 条内存保留、文本选择复制、复制过滤结果、正则过滤、非法正则行为、全局日志 + 当前会话高亮、重连 SSE、手动增量同步、SSE/增量包/单条事件大小日志。
- 占位检查：无 `TODO/TBD/implement later` 之类占位文本。
- 命名一致性：统一使用 `SyncLogEntry` / `SyncLogStore` / `SyncDebugController` / `SyncLogScreen` / `filterRenderedLogs`。

Plan complete and saved to `docs/superpowers/plans/2026-05-12-session-sync-log.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration

2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
