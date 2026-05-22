# Connection Recovery: Design vs Implementation Gap Analysis

> 对照 `docs/superpowers/specs/2026-05-21-connection-recovery-state-machine-design.md`，逐项检查当前实现偏差。

## Gap 1: `StartBackoff` 是空操作，Recovering 无出路

**设计**：
- `Recovering` 状态下，`BackoffExpired` → `Evaluating`
- 被动 backoff 是 fallback 触发器，不阻塞高优先级事件

**实现**：
- `ConnectionAction.StartBackoff -> Unit`（`ConnectionManager.kt:534`）
- 没有 backoff 定时器，没有 `BackoffExpired` 事件
- 进入 `RECOVERING` 后，除非用户手动操作，否则永远卡住

**影响**：WiFi 断开 → SSE 断开 → `RECOVERING` → 无 backoff 定时器 → 无 `Evaluating` → 不探测网关 → 永远卡住

---

## Gap 2: `ReevaluateRoutes` 是空操作，Evaluating 不做路由评估

**设计**：
- `Evaluating` 时应评估 direct/gateway 健康度，按优先级选路由
- 路由评估是 `Evaluating` 的核心职责

**实现**：
- `ConnectionAction.ReevaluateRoutes -> Unit`（`ConnectionManager.kt:533`）
- 路由评估只在 `startConnection()` 里做了一次（初始连接时）
- `UserRetry` / `NetworkAvailable` / `AppForegrounded` 进入 `EVALUATING` 后，没有任何代码重新评估路由

**影响**：运行时网络恢复后，状态机进入 `EVALUATING` 但不做任何事

---

## Gap 3: 初始连接不探测网关，gateway 路径无证据

**设计**：
- `Evaluating` 时应同时评估两条路由
- Route Health Model 要求独立评估每条路由的健康度
- "If recent gateway-routed API evidence already proves usability, prefer that evidence over redundant extra probing"

**实现**：
- `startConnection()` 只探测直连（`apiClient.bridgeStatus()`）
- 直连成功后直接进入直连模式，不记录任何 gateway 证据
- `RouteEvidenceAggregator` 中 gateway 路径永远是 `isUsable=false`
- `selectPreferredRoute()` 在 direct 不可用时返回 null（因为 gateway 无证据）

**影响**：直连失败后无法切换到网关，因为网关从未被探测过

---

## Gap 4: `handleRouteHealthSnapshot` 不走 Reducer，跳过 Evaluating

**设计**：
- `RouteHealthUpdated(snapshot)` 是输入事件，应触发 `Evaluating`
- 路由选择应在 `Evaluating` 状态下按规则执行

**实现**：
- `handleRouteHealthSnapshot()` 直接调用 `handoffToRoute()`（`ConnectionManager.kt:580`）
- 不经过 `dispatch(ConnectionEvent.RouteHealthUpdated)` → `ConnectionReducer`
- 当 gateway 无证据时 `selectPreferredRoute` 返回 null，直接放弃，不做探测

**影响**：路由健康变化时，不走标准状态机流程，可能产生不一致状态

---

## Gap 5: `BackoffExpired` 事件不存在

**设计**：
- `BackoffExpired` 是 `Recovering` → `Evaluating` 的关键触发器
- 被动 backoff 必须不阻塞高优先级事件

**实现**：
- 没有 `BackoffExpired` 事件
- 没有 backoff 定时器实现
- `ConnectionEvent` 中不包含此事件类型

**影响**：`Recovering` 状态无法自动恢复

---

## Gap 6: `FailedRetryable` 状态不存在

**设计**：
- `FailedRetryable(reason)` 是独立状态
- 含义：desired target 存在，但没有可用路由，自动恢复暂时停止
- 可被 `UserRetry` / `AppForegrounded` / `NetworkAvailable` 重新触发

**实现**：
- 只有 `ConnectionPhase.FAILED`，映射为 `ConnectionStatus.ERROR`
- 没有区分 retryable vs permanent failure
- `FAILED` 状态下没有自动恢复机制

**影响**：暂时性网络故障后，用户看到 ERROR 状态，需要手动重试

---

## Gap 7: `SseStreamClosed` / `SseFailed` 触发 `StopActiveTransport`

**设计**：
- SSE failure means "current transport instance is no longer trustworthy"
- SSE failure does NOT prove route is globally unavailable
- Transition: enter `Recovering(route)`, reevaluate route health, choose best route again
- "SseStreamClosed or SseFailed should be treated as re-evaluate route evidence now, not declare network unavailable now"

**实现**：
- `ConnectionReducer` 中 `SseFailed`/`SseStreamClosed` → `RECOVERING` + `StartBackoff`（空操作）
- 但 `handleTransportSignal` 同时记录 `SseSuspicion`，可能触发 `handleRouteHealthSnapshot` → `handoffToRoute` → `startSyncSse(forceRestart=true)`
- 这导致 SSE 断开时上层强制 disconnect 再 reconnect，而不是让底层自动重连

**影响**：SSE 短暂断开时被上层强制重连，丢失正在传输的事件

---

## Gap 8: `NetworkLost` 处理过于简单

**设计**：
- `ConnectedDirect` 下 `NetworkLost` → `Recovering(Direct)`
- `Recovering` 下 `NetworkLost` → stay `Recovering`
- 但 `NetworkAvailable` → `Evaluating` 应立即触发路由重评估

**实现**：
- `NetworkLost` 与 `SseFailed`/`SseStreamClosed` 同等处理 → `RECOVERING`
- `NetworkAvailable` 触发 `EVALUATING` + `onRecoveredConnectivity()` → `requestCatchUpSync()`
- 但 `EVALUATING` 是空操作（Gap 2），所以网络恢复后不会重新选路

**影响**：WiFi 断开再恢复后，不会自动切回直连

---

## Summary: Root Cause Chain

```
WiFi 断开
  → SSE 断开 → SseFailed/SseStreamClosed
  → RECOVERING + StartBackoff(空操作)
  → 无 BackoffExpired → 无 Evaluating
  → 无路由重评估 → gateway 无证据
  → selectPreferredRoute 返回 null
  → 永远卡在 RECOVERING
```

核心缺失：
1. **Recovering → Evaluating 闭环**：backoff 定时器 + BackoffExpired 事件
2. **Evaluating 的路由评估逻辑**：探测 direct + gateway，按优先级选路
3. **初始网关探测**：直连成功时也记录 gateway 证据，为后续 fallback 做准备
