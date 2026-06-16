# Connection State Machine 重构设计

## 1. 动机

当前 `ConnectionManager` + `ConnectionReducer` 实现存在以下问题：

1. **状态散落**：`machineState`、`useGateway`、`evaluating`、`backoffAttempt`、`backoffJob`、`phase` 分布在 6+ 处
2. **逻辑绕过 Reducer**：`evaluateAndConnectInternal()` 直接修改 `machineState`、`useGateway`、`apiClient.baseUrl`，不走 Reducer
3. **并发不安全**：`AtomicBoolean`、`@Volatile` 手动守卫，易遗漏
4. **循环调用**：证据记录 → `RouteHealthUpdated` → 又触发 `evaluateAndConnect()`，需各种 hack 抑制
5. **不可测试**：状态转换逻辑与副作用（网络请求、SSE 连接）耦合，无法单测

## 2. 方案：KStateMachine 声明式状态机

使用 [KStateMachine](https://github.com/KStateMachine/kstatemachine) 库，以声明式 DSL 定义：
- 状态（sealed class，携带上下文数据）
- 事件（sealed class，外部输入全部入队）
- 迁移（`transition<E> { targetState = ... }`）
- 副作用（`onEntry`/`onExit`/`onTriggered` 显式声明 Effect）

## 3. 状态定义

```kotlin
sealed class ConnState : DefaultState() {
    data class Idle(val profile: ServerProfile? = null) : ConnState()

    /** 正在探测路由，tried 记录已尝试且失败的路由 */
    data class Probing(
        val profile: ServerProfile,
        val tried: Set<Route> = emptySet(),
    ) : ConnState()

    /** 路由已选定，SSE 连接中 */
    data class Connecting(
        val profile: ServerProfile,
        val route: Route,
    ) : ConnState()

    /** SSE 已连接，正常工作 */
    data class Connected(
        val profile: ServerProfile,
        val route: Route,
    ) : ConnState()

    /** 连接丢失，等待 backoff 后重新探测 */
    data class Recovering(
        val profile: ServerProfile,
        val attempt: Int = 0,
    ) : ConnState()

    /** 所有路由不可用，等待外部刺激重试 */
    data class Failed(val profile: ServerProfile) : ConnState()

    /** 需要用户配对/修复 */
    data class NeedsRepair(val profile: ServerProfile) : ConnState()
}

sealed class Route {
    data class Direct(val address: String, val port: Int) : Route()
    data class Gateway(val instanceId: String) : Route()
}
```

## 4. 事件定义

```kotlin
sealed class ConnEvent : Event {
    // 用户操作
    data class Connect(val profile: ServerProfile) : ConnEvent()
    data object Disconnect : ConnEvent()
    data object Retry : ConnEvent()
    data class RepairCompleted(val profileId: String) : ConnEvent()

    // 系统信号
    data object NetworkAvailable : ConnEvent()
    data object NetworkLost : ConnEvent()
    data object AppForegrounded : ConnEvent()

    // 探测结果（状态机发起探测 → Executor 执行 → 结果 submit 回来）
    data class ProbeOk(val route: Route) : ConnEvent()
    data class ProbeFail(val route: Route, val reason: String? = null) : ConnEvent()

    // SSE 传输信号
    data class SseConnected(val route: Route) : ConnEvent()
    data class SseFailed(val route: Route, val msg: String? = null) : ConnEvent()
    data class SseStreamClosed(val route: Route) : ConnEvent()

    // 定时器
    data object BackoffExpired : ConnEvent()
}
```

## 5. 副作用定义

```kotlin
sealed class ConnEffect {
    data class ProbeGateway(val instanceId: String) : ConnEffect()
    data class ProbeDirect(val address: String, val port: Int) : ConnEffect()
    data class StartSse(val baseUrl: String, val instanceId: String?) : ConnEffect()
    data object StopSse : ConnEffect()
    data class StartBackoff(val delayMs: Long) : ConnEffect()
    data object StopBackoff : ConnEffect()
    data class SetApiClient(val baseUrl: String, val instanceId: String?) : ConnEffect()
    data object RefreshSessions : ConnEffect()
    data class SaveProfile(val profile: ServerProfile) : ConnEffect()
}
```

## 6. 状态生命周期（onEntry / onExit）

每个状态进入/退出时自动触发的副作用，作为声明式定义的一部分：

| 状态 | onEntry | onExit |
|------|---------|--------|
| Idle | — | — |
| Probing | ProbeGateway | — |
| Connecting | SetApiClient + StartSse | — |
| Connected | RefreshSessions + SaveProfile | — |
| Recovering | StartBackoff(1s * 2^attempt, 上限30s) | StopBackoff |
| Failed | — | — |
| NeedsRepair | — | — |

**关键**：定时器由状态生命周期管理，`onEntry` 启动、`onExit` 取消，超时作为事件 `BackoffExpired` 输入状态机。不需要手动 `startBackoff()`/`stopBackoff()`。

## 7. 状态迁移表

| 当前状态 | 事件 | 目标状态 | 副作用（onTriggered） |
|---------|------|---------|--------|
| Idle | Connect(p) | Probing(p) | — (onEntry 发 ProbeGateway) |
| Idle | Disconnect | Idle | — |
| Probing | ProbeOk(Gateway) | Connecting(p, Gateway) | — (onEntry 发 SetApiClient + StartSse) |
| Probing | ProbeOk(Direct) | Connecting(p, Direct) | — (onEntry 发 SetApiClient + StartSse) |
| Probing | ProbeFail(Gateway) | Probing(p, tried+Gateway) | ProbeDirect |
| Probing | ProbeFail(Direct) | Failed(p) | — |
| Probing | Disconnect | Idle | StopSse |
| Connecting | SseConnected(r) | Connected(p, r) | — (onEntry 发 RefreshSessions + SaveProfile) |
| Connecting | SseFailed | Recovering(p, 0) | — (onEntry 自动 StartBackoff) |
| Connecting | Disconnect | Idle | StopSse |
| Connected | SseFailed | Recovering(p, 0) | — (onEntry 自动 StartBackoff) |
| Connected | SseStreamClosed | Recovering(p, 0) | — (onEntry 自动 StartBackoff) |
| Connected | NetworkLost | Recovering(p, 0) | — (onEntry 自动 StartBackoff) |
| Connected | Disconnect | Idle | StopSse |
| Recovering | BackoffExpired | Probing(p) | — (onEntry 发 ProbeGateway；onExit 自动 StopBackoff) |
| Recovering | NetworkAvailable | Probing(p) | — (onExit 自动 StopBackoff；Probing.onEntry 发 ProbeGateway) |
| Recovering | Disconnect | Idle | StopSse (onExit 自动 StopBackoff) |
| Failed | NetworkAvailable | Probing(p) | — (onEntry 发 ProbeGateway) |
| Failed | AppForegrounded | Probing(p) | — |
| Failed | Retry | Probing(p) | — |
| Failed | Disconnect | Idle | — |
| NeedsRepair | RepairCompleted | Probing(p) | — |
| NeedsRepair | Disconnect | Idle | — |

## 7. 探测策略

WiFi 断开后 gateway 更可能立即可用，因此 **先探 gateway 后探 direct**（串行，避免并发 DNS 打爆网络栈）：

```
Probing → ProbeGateway
  ├─ ProbeOk(Gateway) → Connecting(Gateway)
  └─ ProbeFail(Gateway) → ProbeDirect
       ├─ ProbeOk(Direct) → Connecting(Direct)
       └─ ProbeFail(Direct) → Failed
```

## 8. Backoff 策略（声明式定时器）

定时器由状态生命周期管理，无需手动启停：

- `Recovering.onEntry` → `StartBackoff(1s × 2^attempt, 上限 30s)`
- `Recovering.onExit` → `StopBackoff`
- `BackoffExpired` 事件输入 → 迁移到 `Probing`

**定时器 = 状态的附属物**：进入 Recovering 自动启动，退出自动取消，超时作为事件输入状态机。不在迁移表里手动写 `StartBackoff`/`StopBackoff`。

## 9. 架构

```
┌─────────────────────────────────────────────┐
│              ConnectionManager               │
│  (薄壳：持有 Actor + 暴露 StateFlow 给 UI)    │
├─────────────────────────────────────────────┤
│              ConnectionActor                 │
│  createStateMachine { ... }  ← 声明式定义    │
│  processEvent()             ← 事件入队       │
│  activeStatesFlow()         ← 状态输出       │
├─────────────────────────────────────────────┤
│            EffectExecutor                    │
│  execute(ConnEffect)        ← 执行副作用     │
│  结果通过 actor.processEvent() 回馈          │
└─────────────────────────────────────────────┘
```

- **ConnectionManager**：Hilt 注入的 `@Singleton`，持有 Actor 和 Executor，暴露 `connectionStatus: StateFlow<ConnectionStatus>` 给 UI
- **ConnectionActor**：KStateMachine 实例，声明式定义所有迁移，`processEvent()` 串行处理
- **EffectExecutor**：执行 `ConnEffect`，结果通过 `processEvent()` 回馈到 Actor

## 10. EffectExecutor 执行规则

| Effect | 执行 | 回馈 |
|--------|------|------|
| ProbeGateway(iid) | `isGatewayOnline(iid)` | `ProbeOk(Gateway)` 或 `ProbeFail(Gateway)` |
| ProbeDirect(addr, port) | `isDirectReachable(addr, port)` | `ProbeOk(Direct)` 或 `ProbeFail(Direct)` |
| StartSse(baseUrl, iid) | `syncSseClient.connect(baseUrl)` | SSE 信号通过 collect 自动 `processEvent` |
| StopSse | `syncSseClient.disconnect()` | — |
| StartBackoff(ms) | `delay(ms)` | `BackoffExpired` |
| StopBackoff | cancel backoff job | — |
| SetApiClient(baseUrl, iid) | `apiClient.baseUrl = ...; gatewayInterceptor.instanceId = ...` | — |
| RefreshSessions | `sessionRepository.refreshSessionStatuses()` | — |
| SaveProfile(p) | `profileRepository.save(p)` | — |

## 11. 外部信号接入

```kotlin
// SSE 信号
scope.launch {
    syncSseClient.transportSignals.collect { signal ->
        when (signal) {
            is SyncSseSignal.Connected -> actor.processEvent(ConnEvent.SseConnected(route))
            is SyncSseSignal.Failed -> actor.processEvent(ConnEvent.SseFailed(route, signal.message))
            is SyncSseSignal.StreamClosed -> actor.processEvent(ConnEvent.SseStreamClosed(route))
            else -> Unit
        }
    }
}

// 网络变化
scope.launch {
    networkChangeMonitor.events.collect { event ->
        when (event) {
            NetworkChangeEvent.Available -> actor.processEvent(ConnEvent.NetworkAvailable)
            NetworkChangeEvent.Lost -> actor.processEvent(ConnEvent.NetworkLost)
            NetworkChangeEvent.PathChanged -> actor.processEvent(ConnEvent.NetworkAvailable)
        }
    }
}

// 前后台
scope.launch {
    appForegroundMonitor.isForeground.collect { fg ->
        if (fg) actor.processEvent(ConnEvent.AppForegrounded)
    }
}
```

## 12. 迁移计划

### Phase 1: 引入 KStateMachine + 新状态机
1. 添加 KStateMachine 依赖（`io.github.nsk90:kstatemachine` + `kstatemachine-coroutines`）
2. 新建 `connection/v2/` 包，实现 `ConnState`、`ConnEvent`、`ConnEffect`、`ConnectionActor`、`EffectExecutor`
3. 写单测验证迁移表覆盖所有 case

### Phase 2: 替换 ConnectionManager
4. `ConnectionManager` 改为持有 `ConnectionActor`，删除旧的 `machineState`/`useGateway`/`evaluating` 等散落状态
5. 删除 `ConnectionReducer`、`ConnectionAction`、`ConnectionMachineState` 等旧类型
6. 删除 `RouteEvidenceAggregator`/`RouteEvidence`/`RouteHealthSnapshot`（探测结果直接通过事件回馈，不再需要证据聚合器）

### Phase 3: 验证
7. WiFi 断开 → 自动切网关
8. WiFi 恢复 → 自动切回直连
9. 前后台切换 → 正确恢复
10. 快速断连重连 → 无循环、无崩溃

## 13. 删除的旧代码

- `ConnectionReducer.kt`
- `ConnectionAction.kt`
- `ConnectionMachineState.kt`
- `ConnectionEvent.kt`（替换为新的 `ConnEvent`）
- `RouteEvidenceAggregator.kt`
- `RouteEvidence.kt`
- `RouteHealthSnapshot.kt`
- `RouteEvidenceReporter.kt`
- `ConnectionManager` 中的 `evaluateAndConnect()`/`evaluateAndConnectInternal()`/`startBackoff()`/`stopBackoff()`/`handleRouteHealthSnapshot()`/`handleTransportSignal()` 等方法
