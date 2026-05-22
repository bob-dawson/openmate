# Connection Recovery Gap Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 ConnectionReducer 中 Recovering→Evaluating 闭环 + 初始网关探测，使 WiFi 断开后能自动切换到网关连接。

**Architecture:** 在 ConnectionManager 中实现 backoff 定时器和路由评估逻辑，让 Reducer 的空操作变成有实际副作用的执行。初始连接时同时探测网关，记录 gateway 证据。

**Tech Stack:** Kotlin, Coroutines, ConnectionReducer state machine

---

## File Structure

| File | Responsibility |
|------|---------------|
| `ConnectionReducer.kt` | 处理 `BackoffExpired` 事件，完善 `Evaluating`/`Recovering` 转换 |
| `ConnectionManager.kt` | 实现 backoff 定时器、路由评估逻辑、初始网关探测 |
| `ConnectionAction.kt` | 新增 `ProbeGateway` action |
| `ConnectionEvent.kt` | 已有 `BackoffExpired`，无需改动 |

---

### Task 1: ConnectionReducer 处理 BackoffExpired

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`

当前 `BackoffExpired` 落入 `else -> Result(state, emptyList())` 被忽略。需要让它在 `RECOVERING` 状态下触发 `EVALUATING`。

- [ ] **Step 1: 修改 ConnectionReducer.reduce()**

在 `ConnectionReducer.kt` 的 `reduce()` 方法中，将 `BackoffExpired` 从 `else` 分支提升为显式处理：

```kotlin
ConnectionEvent.BackoffExpired -> when (state.phase) {
    ConnectionPhase.RECOVERING, ConnectionPhase.FAILED -> Result(
        nextState = state.copy(
            phase = ConnectionPhase.EVALUATING,
            recoveryGeneration = state.recoveryGeneration + 1,
        ),
        actions = listOf(ConnectionAction.ReevaluateRoutes),
    )
    else -> Result(state, emptyList())
}
```

- [ ] **Step 2: 验证编译**

Run: `gradle_runner_run_gradle(args=[":app:compileDebugKotlin"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

---

### Task 2: ConnectionManager 实现 backoff 定时器

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

当 Reducer 输出 `StartBackoff` action 时，启动一个定时器，到期后 dispatch `BackoffExpired`。当 Reducer 输出 `ReevaluateRoutes` action 时，执行路由评估。

- [ ] **Step 1: 添加 backoff 相关字段**

在 `ConnectionManager` 类中添加：

```kotlin
private var backoffJob: Job? = null
private var backoffAttempt = 0
private val INITIAL_BACKOFF_MS = 3_000L
private val MAX_BACKOFF_MS = 30_000L
```

- [ ] **Step 2: 实现 startBackoff 和 stopBackoff**

```kotlin
private fun startBackoff() {
    backoffJob?.cancel()
    val delayMs = minOf(INITIAL_BACKOFF_MS * (1L shl minOf(backoffAttempt, 4)), MAX_BACKOFF_MS)
    backoffAttempt++
    backoffJob = scope.launch {
        delay(delayMs)
        dispatch(ConnectionEvent.BackoffExpired)
    }
}

private fun stopBackoff() {
    backoffJob?.cancel()
    backoffJob = null
    backoffAttempt = 0
}
```

- [ ] **Step 3: 在 execute() 中实现 StartBackoff 和 ReevaluateRoutes**

修改 `execute()` 方法：

```kotlin
private fun execute(action: ConnectionAction) {
    when (action) {
        ConnectionAction.ReevaluateRoutes -> {
            stopBackoff()
            scope.launch { evaluateAndConnect() }
        }
        ConnectionAction.StartBackoff -> {
            startBackoff()
        }
        ConnectionAction.StopActiveTransport -> {
            stopBackoff()
            sseEventRepository.disconnect()
            syncSseJob?.cancel()
            syncSseClient.disconnect()
        }
        ConnectionAction.RefreshSessionStatuses -> {
            scope.launch {
                sessionRepository.refreshSessionStatuses()
            }
        }
    }
}
```

- [ ] **Step 4: 在 dispatch() 中重置 backoffAttempt**

当高优先级事件触发 `EVALUATING` 时，重置 backoff 计数器。在 `dispatch()` 方法中，当 `result.nextState.phase == ConnectionPhase.EVALUATING` 时调用 `stopBackoff()`：

```kotlin
private fun dispatch(event: ConnectionEvent) {
    val result = ConnectionReducer.reduce(machineState.value, event)
    machineState.value = result.nextState
    applyMachineState(result.nextState)
    if (result.nextState.phase == ConnectionPhase.EVALUATING) {
        stopBackoff()
    }
    result.actions.forEach { execute(it) }
}
```

- [ ] **Step 5: 验证编译**

Run: `gradle_runner_run_gradle(args=[":app:compileDebugKotlin"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

---

### Task 3: 实现路由评估逻辑 evaluateAndConnect()

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

这是核心逻辑：评估 direct/gateway 健康度，选择最优路由，建立连接。

- [ ] **Step 1: 实现 evaluateAndConnect()**

```kotlin
private suspend fun evaluateAndConnect() {
    val profile = _activeProfile.value ?: return
    val hasIid = profile.instanceId.isNotEmpty()

    val directUsable = isDirectReachable(profile.address, profile.port)

    if (directUsable) {
        routeEvidenceAggregator.record(
            RouteEvidence.ProbeSuccess(
                route = ConnectionRoute.Direct(profile.address, profile.port),
                recordedAt = System.currentTimeMillis(),
            )
        )
        if (useGateway) {
            useGateway = false
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
            gatewayInterceptor.instanceId = null
        }
        machineState.value = machineState.value.copy(
            activeRoute = ConnectionRoute.Direct(profile.address, profile.port),
            phase = ConnectionPhase.CONNECTING,
        )
        applyMachineState(machineState.value)
        startSyncSse(forceRestart = true)
        return
    }

    routeEvidenceAggregator.record(
        RouteEvidence.ProbeFailure(
            route = ConnectionRoute.Direct(profile.address, profile.port),
            recordedAt = System.currentTimeMillis(),
            message = "probe failed",
        )
    )

    if (hasIid && isGatewayOnline(profile.instanceId)) {
        routeEvidenceAggregator.record(
            RouteEvidence.ProbeSuccess(
                route = ConnectionRoute.Gateway(profile.instanceId),
                recordedAt = System.currentTimeMillis(),
            )
        )
        useGateway = true
        apiClient.baseUrl = GATEWAY_URL
        gatewayInterceptor.instanceId = profile.instanceId
        machineState.value = machineState.value.copy(
            activeRoute = ConnectionRoute.Gateway(profile.instanceId),
            phase = ConnectionPhase.CONNECTING,
        )
        applyMachineState(machineState.value)
        startSyncSse(forceRestart = true)
        startDirectCheckLoop()
        return
    }

    if (hasIid) {
        routeEvidenceAggregator.record(
            RouteEvidence.ProbeFailure(
                route = ConnectionRoute.Gateway(profile.instanceId),
                recordedAt = System.currentTimeMillis(),
                message = "gateway offline",
            )
        )
    }

    machineState.value = machineState.value.copy(phase = ConnectionPhase.FAILED)
    applyMachineState(machineState.value)
    _errorMessage.value = "No available route"
}
```

- [ ] **Step 2: 验证编译**

Run: `gradle_runner_run_gradle(args=[":app:compileDebugKotlin"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

---

### Task 4: 初始连接时探测网关

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

当前 `startConnection()` 直连成功后不探测网关。需要在直连成功时异步探测网关，记录 gateway 证据，为后续 fallback 做准备。

- [ ] **Step 1: 在 startConnection() 直连成功后异步探测网关**

在 `startConnection()` 方法中，直连成功并调用 `startSseConnections(profile)` 之后，如果 profile 有 instanceId，异步探测网关并记录证据：

找到 `startSseConnections(profile)` 调用后，添加：

```kotlin
if (hasIid) {
    scope.launch {
        if (isGatewayOnline(profile.instanceId)) {
            routeEvidenceAggregator.record(
                RouteEvidence.ProbeSuccess(
                    route = ConnectionRoute.Gateway(profile.instanceId),
                    recordedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `gradle_runner_run_gradle(args=[":app:compileDebugKotlin"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

---

### Task 5: handleRouteHealthSnapshot 走标准 Reducer 流程

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

当前 `handleRouteHealthSnapshot()` 直接调用 `handoffToRoute()`，绕过 Reducer。改为通过 `dispatch(RouteHealthUpdated)` 触发标准流程。

- [ ] **Step 1: 修改 handleRouteHealthSnapshot()**

将 `handleRouteHealthSnapshot()` 改为通过 dispatch 触发：

```kotlin
private fun handleRouteHealthSnapshot(snapshot: com.openmate.app.connection.RouteHealthSnapshot) {
    dispatch(ConnectionEvent.RouteHealthUpdated(snapshot.revision))
}
```

- [ ] **Step 2: 在 ConnectionReducer 中处理 RouteHealthUpdated**

在 `ConnectionReducer.reduce()` 中添加 `RouteHealthUpdated` 的显式处理：

```kotlin
is ConnectionEvent.RouteHealthUpdated -> when (state.phase) {
    ConnectionPhase.CONNECTED, ConnectionPhase.GATEWAY_CONNECTED -> {
        // 保持当前状态，route evidence 变化不足以打断活跃连接
        Result(state, emptyList())
    }
    ConnectionPhase.RECOVERING, ConnectionPhase.FAILED -> Result(
        nextState = state.copy(
            phase = ConnectionPhase.EVALUATING,
            recoveryGeneration = state.recoveryGeneration + 1,
        ),
        actions = listOf(ConnectionAction.ReevaluateRoutes),
    )
    else -> Result(state, emptyList())
}
```

注意：`ConnectedDirect`/`ConnectedGateway` 在当前实现中统一为 `CONNECTED`，所以这里用 `CONNECTED` 覆盖。活跃连接下路由证据变化不触发重评估，避免打断正常工作。只在 RECOVERING/FAILED 下才触发 Evaluating。

- [ ] **Step 3: 删除 handoffToRoute() 和 selectPreferredRoute()**

这两个方法不再需要，因为路由选择逻辑已移入 `evaluateAndConnect()`。删除 `handoffToRoute()` 和 `selectPreferredRoute()` 方法。

- [ ] **Step 4: 验证编译**

Run: `gradle_runner_run_gradle(args=[":app:compileDebugKotlin"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

---

### Task 6: 清理 handleTransportSignal 中的重复 handoff 逻辑

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

当前 `handleTransportSignal()` 中 `SseStreamClosed` 和 `SseFailed` 记录 `SseSuspicion` 后 dispatch 事件，这会触发 Reducer 进入 RECOVERING + StartBackoff。这是正确的——backoff 到期后会自动 evaluateAndConnect()。

但 `SseEventReceived` 记录 `SsePositive` 后 dispatch `SseEventReceived` 事件，这会触发 `routeEvidenceAggregator.snapshot` 变化 → `handleRouteHealthSnapshot` → dispatch `RouteHealthUpdated`。在 Task 5 之后，`RouteHealthUpdated` 在 CONNECTED 状态下不做任何事，所以这是安全的。

- [ ] **Step 1: 确认 handleTransportSignal 不需要修改**

当前逻辑已经正确：
- SSE 断开 → 记录 Suspicion → dispatch SseFailed/SseStreamClosed → RECOVERING + StartBackoff → backoff 到期 → Evaluating → evaluateAndConnect()
- SSE 收到事件 → 记录 Positive → dispatch SseEventReceived → stay CONNECTED

无需修改。

---

### Task 7: 验证端到端

- [ ] **Step 1: 编译 debug APK**

Run: `gradle_runner_run_gradle(args=[":app:assembleDebug"], cwd="D:\\openmate")`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装到设备**

Run: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: 手动验证**

1. 打开 app，连接到 Bridge（直连模式）
2. 确认 SSE 连接正常，增量同步正常
3. 关闭 WiFi
4. 观察是否自动切换到网关连接
5. 重新打开 WiFi
6. 观察是否自动切回直连
