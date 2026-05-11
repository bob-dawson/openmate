# Session Retry SSE + 轮询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android 会话详情页通过 SSE 立即显示 retry 状态，同时保留轮询兜底，避免移动端因 SSE 不稳定而丢失状态展示。

**Architecture:** 在 `SessionRepositoryImpl` 中增加按会话维度维护的内存 retry 状态流，`SessionEventHandler` 在处理 `session.status` 事件时更新这份状态流，`SessionDetailViewModel` 订阅该流获得实时更新。现有 `getSessionRetryStatus()` 轮询路径保留不变，继续作为补偿机制，增量同步继续只负责消息最终一致性。

**Tech Stack:** Kotlin, Coroutines Flow, Hilt, Room, Robolectric, Google Truth

---

### Task 1: 为 ViewModel 增加 SSE retry 实时测试

**Files:**
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: 写失败测试，证明 SSE retry 更新无需等待轮询**

```kotlin
@Test
fun loadSession_updatesRetryStatusImmediatelyFromObservedRetryFlow() = runTest(dispatcher) {
    val repository = FakeSessionMessageRepository(
        recentWindow = listOf(message("m1", timeCreated = 1)),
        lastSeq = null,
    )
    val sessionRepository = FakeSessionRepository()
    val viewModel = createViewModel(
        sessionMessageRepository = repository,
        sessionRepository = sessionRepository,
    )

    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.messages.value.isNotEmpty() }

    sessionRepository.emitRetryStatus(
        SessionRetryStatus(
            sessionId = SESSION_ID,
            attempt = 3,
            message = "Provider overloaded",
            next = System.currentTimeMillis() + 15_000,
        )
    )

    waitUntil { viewModel.sessionRetryStatus.value?.attempt == 3 }

    assertThat(viewModel.sessionRetryStatus.value?.message).isEqualTo("Provider overloaded")
}
```

- [ ] **Step 2: 运行单测并确认按预期失败**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.loadSession_updatesRetryStatusImmediatelyFromObservedRetryFlow"`

Expected: FAIL，原因是 `FakeSessionRepository`/`SessionRepository` 还没有提供 `observeSessionRetryStatus()` 或 ViewModel 未订阅该流。

- [ ] **Step 3: 为 fake 仓库增加最小测试支撑代码**

```kotlin
private class FakeSessionRepository(
    private val retryStatus: SessionRetryStatus? = null,
) : SessionRepository {
    private val retryStatusFlow = MutableStateFlow(retryStatus)

    override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> = retryStatusFlow

    override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? = retryStatusFlow.value

    fun emitRetryStatus(status: SessionRetryStatus?) {
        retryStatusFlow.value = status
    }
}
```

- [ ] **Step 4: 再次运行该单测，确认仍失败且失败点已转为生产代码未实现**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.loadSession_updatesRetryStatusImmediatelyFromObservedRetryFlow"`

Expected: FAIL，原因是 `SessionDetailViewModel` 仍未订阅 `observeSessionRetryStatus()`。

### Task 2: 实现 SessionRepository 的 retry 观察流

**Files:**
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/SessionRepository.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SessionRepositoryImpl.kt`
- Test: `android/core/data/src/test/java/com/openmate/core/data/repository/SessionRepositoryImplTest.kt`

- [ ] **Step 1: 写失败测试，证明仓库可以观察 retry 状态变化**

```kotlin
@Test
fun observeSessionRetryStatus_emitsUpdatedRetryState() = runTest {
    val repository = SessionRepositoryImpl(api = fakeApi, dbProvider = fakeDbProvider)

    val values = mutableListOf<SessionRetryStatus?>()
    val job = backgroundScope.launch {
        repository.observeSessionRetryStatus("session-1").take(2).toList(values)
    }

    repository.updateObservedRetryStatus(
        sessionId = "session-1",
        status = SessionRetryStatus(
            sessionId = "session-1",
            attempt = 2,
            message = "rate limited",
            next = 123L,
        ),
    )

    job.join()

    assertThat(values).containsExactly(null, SessionRetryStatus(
        sessionId = "session-1",
        attempt = 2,
        message = "rate limited",
        next = 123L,
    )).inOrder()
}
```

- [ ] **Step 2: 运行仓库单测并确认失败**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.repository.SessionRepositoryImplTest.observeSessionRetryStatus_emitsUpdatedRetryState"`

Expected: FAIL，因为仓库还没有 `observeSessionRetryStatus()` 与内存状态更新入口。

- [ ] **Step 3: 在领域接口中加入观察接口**

```kotlin
interface SessionRepository {
    fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?>
    suspend fun getSessionRetryStatus(id: String): SessionRetryStatus?
}
```

- [ ] **Step 4: 在仓库实现中加入最小内存状态流与更新入口**

```kotlin
private val retryStatuses = MutableStateFlow<Map<String, SessionRetryStatus>>(emptyMap())

override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> {
    return retryStatuses.map { it[id] }
}

fun updateObservedRetryStatus(sessionId: String, status: SessionRetryStatus?) {
    retryStatuses.value = retryStatuses.value.toMutableMap().apply {
        if (status == null) remove(sessionId) else put(sessionId, status)
    }
}
```

- [ ] **Step 5: 运行仓库单测并确认通过**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.repository.SessionRepositoryImplTest.observeSessionRetryStatus_emitsUpdatedRetryState"`

Expected: PASS

### Task 3: 让 SessionEventHandler 用 SSE 更新 retry 内存态

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sse/SessionEventHandler.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SessionRepositoryImpl.kt`
- Test: `android/core/data/src/test/java/com/openmate/core/data/sse/SessionEventHandlerTest.kt`

- [ ] **Step 1: 写失败测试，证明 session.status=retry 会写入 retry 内存态，非 retry 会清除**

```kotlin
@Test
fun handle_sessionStatusRetry_updatesObservedRetryState() = runTest {
    val retryStore = SessionRetryStateStore()
    val handler = SessionEventHandler(dbProvider = fakeDbProvider, retryStateStore = retryStore)

    handler.handle(
        type = "session.status",
        event = sseEvent(
            sessionId = "session-1",
            statusJson = """{"type":"retry","attempt":2,"message":"rate limited","next":1000}"""
        )
    )

    assertThat(retryStore.observe("session-1").first()?.message).isEqualTo("rate limited")

    handler.handle(
        type = "session.status",
        event = sseEvent(
            sessionId = "session-1",
            statusJson = """{"type":"busy"}"""
        )
    )

    assertThat(retryStore.observe("session-1").first()).isNull()
}
```

- [ ] **Step 2: 运行单测并确认失败**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sse.SessionEventHandlerTest.handle_sessionStatusRetry_updatesObservedRetryState"`

Expected: FAIL，因为当前 `SessionEventHandler` 没有 retry 内存态依赖。

- [ ] **Step 3: 给 SessionEventHandler 注入 retry 状态更新依赖并实现最小逻辑**

```kotlin
when (statusType) {
    "retry" -> {
        retryStateStore.update(
            sessionID,
            SessionRetryStatus(
                sessionId = sessionID,
                attempt = statusObj["attempt"]?.jsonPrimitive?.content?.toIntOrNull(),
                message = statusObj["message"]?.jsonPrimitive?.content.orEmpty(),
                next = statusObj["next"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        )
        SessionStatus.BUSY.name
    }
    else -> {
        retryStateStore.update(sessionID, null)
        if (statusType == "busy") SessionStatus.BUSY.name else SessionStatus.IDLE.name
    }
}
```

- [ ] **Step 4: 运行单测并确认通过**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.sse.SessionEventHandlerTest.handle_sessionStatusRetry_updatesObservedRetryState"`

Expected: PASS

### Task 4: 让 SessionDetailViewModel 订阅 retry 状态流并保留轮询兜底

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: 在 ViewModel 中写最小订阅逻辑**

```kotlin
private var observeRetryStatusJob: Job? = null

private fun observeRetryStatus(sessionId: String) {
    observeRetryStatusJob?.cancel()
    observeRetryStatusJob = viewModelScope.launch(Dispatchers.IO) {
        sessionRepository.observeSessionRetryStatus(sessionId).collect { status ->
            _sessionRetryStatus.value = status
            recalculateMessageDerivedState(messageWindowState.messages)
        }
    }
}
```

- [ ] **Step 2: 在 `loadSession()` 中启动订阅，并在 `onCleared()` 中取消**

```kotlin
observeRetryStatus(sessionID)
```

```kotlin
observeRetryStatusJob?.cancel()
```

- [ ] **Step 3: 保持轮询兜底逻辑，但只在结果非空或当前状态已存在时覆盖**

```kotlin
private suspend fun refreshRetryStatus(sessionId: String) {
    val latest = sessionRepository.getSessionRetryStatus(sessionId)
    if (latest != null || _sessionRetryStatus.value != null) {
        _sessionRetryStatus.value = latest
    }
}
```

- [ ] **Step 4: 运行 ViewModel 单测并确认通过**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest.loadSession_updatesRetryStatusImmediatelyFromObservedRetryFlow"`

Expected: PASS

### Task 5: 跑回归测试并确认行为

**Files:**
- Test: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- Test: `android/core/data/src/test/java/com/openmate/core/data/repository/SessionRepositoryImplTest.kt`
- Test: `android/core/data/src/test/java/com/openmate/core/data/sse/SessionEventHandlerTest.kt`

- [ ] **Step 1: 运行会话详情相关单测**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --no-daemon --tests "com.openmate.feature.session.SessionDetailViewModelTest"`

Expected: PASS

- [ ] **Step 2: 运行 core:data 相关单测**

Run: `./gradlew.bat :core:data:testDebugUnitTest --no-daemon --tests "com.openmate.core.data.repository.SessionRepositoryImplTest" --tests "com.openmate.core.data.sse.SessionEventHandlerTest"`

Expected: PASS

- [ ] **Step 3: 人工检查点**

确认以下行为：

```text
1. 收到 retry SSE 时，详情页立即显示 retry 卡片
2. SSE 中断时，轮询仍能补回 retry 状态
3. retry 结束后，详情页恢复普通 busy/idle 展示
4. 未改动 session list/workspace list 的粗状态语义
```
