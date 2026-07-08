# 实时消息（Live Messages）设计

## 背景

Android 端在开启"实时消息"开关后，通过 SSE 接收 `message.part.updated` 事件实时显示流式输出，不等待增量同步。但由于移动端网络特性，SSE 不能保证始终在线，增量同步仍然是最终一致性的保障。

当前实现中 live parts 与增量同步消息的协调机制不清晰，导致消息闪烁、消失、顺序混乱等问题。本文档重新设计协调机制。

## 设计原则

1. **增量同步是唯一真相源**：所有消息最终以增量同步写入 Room DB 的数据为准
2. **live parts 是临时增强**：仅在 SSE 稳定时提供更快的实时更新体验，不持久化
3. **生命周期绑定消息级别**：live parts 的创建/清除以消息的 `completedAt` 为准，不以单个 part 的 `isComplete` 为准

## 数据源

### 增量同步（主要）
- 触发：SSE `message.*` / `session.next.*` / `todo.*` 事件 → `_messageSyncNeeded` → 300ms debounce → `incrementalSync`
- 结果：写入 Room DB → emit `syncEvents` → `applySyncResult` → 更新 `_messages` StateFlow
- 保证最终一致性

### live parts（增强）
- 触发：SSE `message.part.updated` / `message.part.delta` / `message.part.removed` → `_livePartEvents`
- 结果：更新 `_liveParts` StateFlow（内存中，不持久化）
- 仅在 SSE 在线时提供实时流式显示

## 消息显示规则

### UI 组成

```
displayMessages = dedupedBase + liveMessages
```

### dedupedBase（去重后的同步消息）

**规则**：assistant 消息 `completedAt == null`（运行中）且在 live parts 中有对应 `messageId` → **隐藏**（由 live parts 替代显示）

```kotlin
val liveMessageIds = liveParts.map { it.messageId }.toSet()
val dedupedBase = base.filterNot { 
    it.id in liveMessageIds && it.type == "assistant" && it.completedAt == null 
}
```

- user 消息：**始终显示**
- assistant 消息已完成（`completedAt != null`）：**始终显示**
- assistant 消息运行中（`completedAt == null`）且有 live parts：**隐藏**

### liveMessages（追加在末尾的 live parts）

**双重过滤**，确保无缝衔接：

```kotlin
// 1. 不显示已有 user 消息的 live parts
val userMsgIds = dedupedBase.filter { it.type == "user" }.map { it.id }.toSet()
// 2. 不显示已完成消息（completedAt != null）的 live parts（同步消息已替代）
val completedMsgIds = dedupedBase.filter { it.completedAt != null }.map { it.id }.toSet()
val visibleLiveParts = liveParts.filter { it.messageId !in userMsgIds && it.messageId !in completedMsgIds }
```

**为什么 UI 层要过滤 `completedMsgIds`**：

`_messages` 和 `_liveParts` 是两个独立的 StateFlow，更新时有时间差。如果只依赖 `cleanupSyncedLiveParts` 清除 live parts：

1. `_messages` 更新（含完成消息）→ 重组 → 同步消息显示 + live parts 还在 → **重复闪烁**
2. `cleanupSyncedLiveParts` 执行 → `_liveParts` 更新 → 重组 → live parts 清除

UI 层基于 `completedAt` 过滤 live parts 后，步骤 1 的重组中 `visibleLiveParts` 已排除完成消息的 live parts → **无重复，一步到位**。`cleanupSyncedLiveParts` 只是后续内存清理。

### 状态转换

```
消息创建 → SSE message.part.updated → live part 创建（isComplete=false）
    ↓ 流式更新（多次 message.part.updated）
live part 持续更新文本
    ↓ 增量同步拉到消息（completedAt=null）
dedupedBase 隐藏 assistant 消息，live parts 显示
    ↓ 消息完成
增量同步拉到消息（completedAt!=null）
    ↓ applySyncResult
1. _messages 更新（含 completedAt）
2. cleanupSyncedLiveParts 清除该消息所有 live parts
3. dedupedBase 不再隐藏（completedAt != null）
4. 同步消息显示
```

## live parts 生命周期

### 创建
- SSE 收到 `message.part.updated` / `delta` → 创建/更新 `LivePart`

### 更新
- `delta`：追加文本
- `updated`：替换完整文本，`isComplete` 从 `part.time.end` 推断

### 清除（cleanupSyncedLiveParts）
- **角色**：纯内存清理，不参与 UI 显示决策（UI 层已基于 `completedAt` 过滤）
- **触发时机**：`applySyncResult` 和 `rebuildInitialWindow` 之后
- **清除条件**：消息 `completedAt != null` → 清除该消息的所有 live parts

```kotlin
private fun cleanupSyncedLiveParts() {
    val completedMsgIds = _messages.value
        .filter { it.completedAt != null }
        .map { it.id }.toSet()
    val filtered = _liveParts.value.filter { it.messageId !in completedMsgIds }
    if (filtered.size != _liveParts.value.size) {
        _liveParts.value = filtered
    }
}
```

> 注意：即使 cleanup 没执行，UI 也不会重复显示，因为 `displayMessages` 计算时基于 `completedAt` 过滤了 live parts。

### 全量清除
- 切换会话 / 退出会话详情页：`_liveParts.value = emptyList()`

## 消息排序

### SessionMessageWindowManager.apply()
每次应用同步变更后，按 `timeCreated` + `id` 排序：

```kotlin
messages.distinctBy { it.id }
    .sortedWith(compareBy(SessionMessage::timeCreated, SessionMessage::id))
```

### displayMessages
- `dedupedBase`：已排序（来自 `_messages`）
- `liveMessages`：追加在 `dedupedBase` 末尾（`timeCreated=0`，不参与排序）

## 自动滚动

### 触发条件
- `_messages` 变化（消息数量增加或最后一条消息内容更新）
- `_liveParts` 变化（文本长度变化或新增 live part）
- 用户在列表底部（`shouldFollow = true`）

### 滚动目标
`listState.layoutInfo.totalItemsCount - 1`（包含 live parts 的实际 item 数）

```kotlin
suspend fun scrollToBottom() {
    val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    runAutoScroll(
        messageCount = lastIndex,
        canScrollForward = { listState.canScrollForward },
        onStarted = autoFollowTracker::onAutoScrollStarted,
        onEnded = autoFollowTracker::onAutoScrollEnded,
        scroll = { index -> listState.animateScrollToItem(index) },
    )
}
```

### live parts 内容变化监听

```kotlin
val liveContentKey = remember(liveParts) {
    liveParts.joinToString("|") { "${it.partId}:${it.text.length}" }
}
LaunchedEffect(liveContentKey) {
    if (liveParts.isNotEmpty()) {
        autoFollowTracker.onContentUpdated()
    }
}
```

## syncEvents 防丢失

### 问题
首次进入会话时，`observeSyncEventJob` 的 collect 协程可能还没开始，`incrementalSync` emit 的 syncEvents 丢失（SharedFlow `replay=0`）。

### 修复
- `syncEvents` 使用 `replay=1`：确保最后一个事件不丢失
- `observeSyncEventJob` collect 内层 try-catch：防止单次 `applySyncResult` 异常导致整个 collect 结束

## 首次进入会话

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    rebuildInitialWindow(sessionID)  // 从 DB 加载
    if (有本地消息 && lastSeq > 0) {
        try { incrementalSync(sessionID) } catch { ... }
        rebuildInitialWindow(sessionID)  // 同步后再 reload
    } else {
        try { applySyncResult(initSync(...)) } catch { ... }
    }
}
```

- `incrementalSync` 的 try-catch 独立，`rebuildInitialWindow` 始终执行
- 不依赖 syncEvents 来接收首次同步结果

## 推理消息渲染

### 共享 Composable
`ReasoningBlock` 统一渲染实时和同步的推理消息：

| 参数 | 实时（LivePartItem） | 同步（AssistantMessageItem） |
|------|---------------------|---------------------------|
| `defaultExpanded` | `true` | `showLiveMessages` |
| `showProgress` | `!isComplete` | `false` |
| `filterRedacted` | `false`（连贯显示） | `true` |

## 涉及文件

| 文件 | 修改内容 |
|------|---------|
| `SessionDetailViewModel.kt` | `cleanupSyncedLiveParts`、`observeLiveParts`、首次进入逻辑 |
| `SessionDetailScreen.kt` | `displayMessages` 去重/过滤、自动滚动 |
| `SessionMessageWindowManager.kt` | `apply()` 排序 |
| `SessionMessageRenderer.kt` | `ReasoningBlock` 共享 Composable |
| `EventDispatcher.kt` | `isComplete` 从 `part.time.end` 推断 |
| `SessionMessageRepositoryImpl.kt` | `syncEvents` replay=1 |
