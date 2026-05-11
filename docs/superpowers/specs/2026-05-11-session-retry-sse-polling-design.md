# 会话 retry 状态的 SSE + 轮询 展示设计

## 背景

Android 会话详情页当前已经能通过轮询 `/session/status` 获取 retry 状态，但存在两个问题：

1. `session.status` SSE 中的 retry 细节被压扁成普通 `BUSY`，无法即时显示 `message / attempt / next`
2. 详情页只能等待轮询补数据，导致 retry 提示出现不及时，用户容易误判为会话卡住

同时，移动端不能假设 SSE 始终稳定，任何状态展示都不能只依赖 SSE。

## 目标

1. 会话详情页在收到 retry 状态时尽快显示“自动重试中/等待重试”提示
2. 在 SSE 断连、后台切换或事件遗漏时，轮询仍能补齐 retry 状态，避免信息长期缺失
3. 不扩大改造范围，不重写现有 SessionStatus 机制，不改变列表页和工作区页的粗状态逻辑

## 非目标

1. 不把 retry 细节持久化到 Room
2. 不重构全部 session 状态流
3. 不让增量同步承担 retry 详情来源职责；增量同步继续只负责消息最终一致性

## 方案概述

本次采用“`SSE` 提升实时性，`轮询` 保底补偿”的双路径方案。

### 1. SSE 路径

- `SessionEventHandler` 继续处理 `session.status`
- 当 `status.type == "retry"` 时：
  - DB 中的 session 粗状态仍保持 `BUSY`
  - 额外把 `message / attempt / next` 写入一份内存中的 retry 状态仓库
- 当 `status.type` 变为 `busy`、`idle` 或其他非 retry 状态时：
  - 清除该会话的内存 retry 状态

这条路径只负责“更快显示”，不承担唯一真相来源职责。

### 2. 轮询路径

- `SessionDetailViewModel` 保留现有 `refreshRetryStatus(sessionId)` 调用
- 在 `loadSession()`、手动 `refresh()`、定时 `poll`、`compact()` 后继续调用 `getSessionRetryStatus(sessionId)`
- 当轮询拿到 retry 状态时，同步更新 ViewModel 展示状态
- 当轮询确认当前不是 retry 时，清除展示状态

这条路径负责在 SSE 不可靠时补齐状态，保证最终一致。

### 3. 增量同步职责

- 增量同步继续负责消息列表内容的最终一致性
- 本次不从消息增量同步中提取 retry 详情
- retry 展示与消息同步保持解耦，避免把状态问题混入消息模型改造

## 组件改动

### SessionRepository

新增接口：

- `observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?>`

保留已有接口：

- `getSessionRetryStatus(id: String): SessionRetryStatus?`

前者用于 SSE 实时推送，后者用于轮询兜底。

### SessionRepositoryImpl

- 新增按 `sessionId` 存储的内存 retry 状态流
- 实现 `observeSessionRetryStatus()`
- 暴露最小更新入口给 SSE 处理链使用
- 保留现有 `getSessionRetryStatus()` 的远端读取逻辑

### SessionEventHandler

- 在处理 `session.status` 时，除了更新 DB 粗状态外，同时更新内存 retry 状态
- `retry` 写入详情
- 非 `retry` 清空详情

### SessionDetailViewModel

- `loadSession()` 时开始订阅 `observeSessionRetryStatus(sessionId)`
- 收到 SSE retry 状态时立即更新 `_sessionRetryStatus`
- 继续保留 `refreshRetryStatus(sessionId)` 作为轮询兜底
- 当 retry 状态存在时，继续压住普通 busy 计时逻辑

## 数据流

### 实时路径

`SSE session.status(retry)`
→ `SessionEventHandler`
→ `SessionRepositoryImpl` 内存 retry 状态流
→ `SessionDetailViewModel.sessionRetryStatus`
→ `SessionRetryCard`

### 补偿路径

`poll / refresh / compact / loadSession`
→ `SessionRepository.getSessionRetryStatus(sessionId)`
→ `SessionDetailViewModel.sessionRetryStatus`
→ `SessionRetryCard`

## 错误处理

- SSE 更新失败时，不影响现有 DB 状态更新与后续轮询补偿
- 轮询失败时，保持当前内存 retry 展示不被异常清空，等待下一轮刷新
- 若 SSE 与轮询结果短时间不一致，以后到达的数据覆盖先到达的数据；轮询负责最终补齐

## 测试策略

1. ViewModel 失败测试：加载会话后，SSE 推送 retry 状态，`sessionRetryStatus` 应立即更新，不等待轮询
2. ViewModel 保底测试：没有 SSE 推送时，轮询拿到 retry 状态后仍能更新展示
3. Repository/EventHandler 测试：`session.status=retry` 会写入内存 retry 状态，非 retry 会清除

## 取舍

本方案不追求把 retry 状态纳入统一持久化模型，而是选择最小改动：

- 用 SSE 解决“显示不及时”
- 用轮询解决“移动端 SSE 不可靠”
- 用增量同步继续保证消息最终一致，但不让它承担 retry 详情职责

这样可以在不扩大重构范围的前提下，修复当前最影响用户感知的问题。
