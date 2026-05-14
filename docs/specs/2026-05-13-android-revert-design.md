# Android 消息回滚（Revert）功能设计

## 概述

实现 opencode 的消息回滚功能，与 Web/TUI 保持一致。Revert 是**就地回滚**——在原 session 内删除指定消息之后的所有内容，并撤销文件变更。配套 Unrevert 可恢复。

**不实现 Fork**（对话分叉，共享同一 worktree，无文件隔离，对移动端价值有限）。

## opencode Revert 机制

### API

| API | 方法 | 路径 | Body | 返回 |
|-----|------|------|------|------|
| Revert | POST | `/session/{sessionID}/revert` | `{ messageID, partID? }` | `Session.Info` |
| Unrevert | POST | `/session/{sessionID}/unrevert` | 无 | `Session.Info` |

两个 API 均通过 Bridge proxy fallback 转发到 opencode，**Bridge 无需改动**。

### Revert 流程（服务端）

1. 找到 target messageID 之后的所有消息（含 target 本身）
2. 收集这些消息中的所有 `patch` part（文件变更记录）
3. 通过 Snapshot 系统回滚文件（`git checkout` 旧版本）
4. 在 session 上设置 `revert` 字段：`{ messageID, partID?, snapshot?, diff? }`
5. 触发 SSE 事件：`session.updated`（带 revert 字段）
6. **不立即删除消息**——消息在 revert 状态下仅被隐藏

### Unrevert 流程（服务端）

1. 恢复 snapshot 对应的文件状态
2. 清除 session 的 `revert` 字段
3. 触发 SSE 事件：`session.updated`（revert 字段清空）

### 消息实际删除时机

当用户在 revert 状态下发送新 prompt 时，opencode 的 `prompt.loop` 会调用 `revert.cleanup()`：
1. 遍历消息，删除 `messageID` 及之后的所有消息
2. 如果有 `partID`，只删除该 part 及之后的部分
3. 触发 `message.removed` / `message.part.removed` 同步事件

## 涉及的改动

### 模块级大纲

| 模块 | 改动 |
|------|------|
| core/domain | Session 添加 revert 字段；SessionRepository 添加 revert/unrevert |
| core/network | OpencodeApiClient 添加 revertSession/unrevertSession |
| core/data | SessionRepositoryImpl 实现 revert/unrevert；Session DTO→Domain 映射添加 revert |
| core/database | SessionEntity 添加 revert 字段 |
| core/data/sync | EventReplayer 添加 message.removed / message.part.removed 处理；ReplayChange 添加 Delete |
| core/data | SessionMessageRepositoryImpl 增量同步写入逻辑处理 Delete change |
| feature/session | ViewModel 添加 revert/unrevert；UI 添加回滚菜单项和 revert 状态指示 |

---

## 步骤一：EventReplayer 支持删除事件

### 问题

当前 EventReplayer 完全没有处理 `message.removed` 和 `message.part.removed` 事件。revert cleanup 后产生的删除事件会被静默丢弃。

### 改动

**`ReplayChange` 添加 Delete 类型：**

```kotlin
sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(...) : ReplayChange()
    data class Delete(val id: String) : ReplayChange()
}
```

**EventReplayer.processEvent 添加两个分支：**

```kotlin
"message.removed" -> {
    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return
    changes += ReplayChange.Delete(messageId)
}

"message.part.removed" -> {
    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return
    val partId = props["partID"]?.jsonPrimitive?.contentOrNull ?: return
    // 简化处理：找到包含该 part 的 assistant 消息，从 content 中移除该 part
    // 如果 content 为空则删除整条消息
    val entity = loader(DbLoader.Action.LoadById(messageId)) ?: return
    val data = runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return
    val content = data["content"]?.jsonArray ?: return
    val filtered = content.filterNot { part ->
        part.jsonObject["id"]?.jsonPrimitive?.contentOrNull == partId
    }
    if (filtered.isEmpty()) {
        changes += ReplayChange.Delete(messageId)
    } else {
        val updated = data.toMutableMap()
        updated["content"] = JsonArray(filtered)
        changes += ReplayChange.Update(messageId, entity.type, JsonObject(updated), entity.timeUpdated)
    }
}
```

**SessionMessageRepositoryImpl 增量同步写入处理 Delete：**

在 `coalesced` 循环和 `withTransaction` 块中添加：

```kotlin
is ReplayChange.Delete -> {
    db.sessionMessageDao().delete(change.id)
    batchChanges += SessionMessageSyncChange.Remove(change.id)
    allAppliedChanges += SessionMessageSyncChange.Remove(change.id)
}
```

**SessionMessageSyncChange.Remove** 已存在，无需修改。

### 验证

- EventReplayerTest 添加 `message.removed` 和 `message.part.removed` 的单元测试
- 手动验证：在 TUI 中 revert → 发新消息触发 cleanup → Android 增量同步应正确删除消息

---

## 步骤二：Session 模型添加 revert 状态

### 改动

**Session domain model 添加 revert 字段：**

```kotlin
data class Session(
    ...,
    val revert: SessionRevert? = null,
)

data class SessionRevert(
    val messageID: String,
    val partID: String? = null,
)
```

**SessionDto → Session 映射** 已有 `SessionRevertDto`，只需在 `toDomain()` 中映射：

```kotlin
revert = revert?.let { SessionRevert(messageID = it.messageID ?: "", partID = it.partID) },
```

**SessionEntity 添加 revert 字段：**

```kotlin
data class SessionEntity(
    ...,
    val revertMessageID: String? = null,
    val revertPartID: String? = null,
)
```

映射：
- Entity → Domain：`revert = revertMessageID?.let { SessionRevert(it, revertPartID) }`
- Domain → Entity：`revertMessageID = revert?.messageID, revertPartID = revert?.partID`

**DB version 递增**（当前 v18 → v19），使用 fallbackToDestructiveMigration。

### 验证

- 调用 revert API 后，Android session 详情页应能看到 revert 状态
- 调用 unrevert 后，revert 字段应清空

---

## 步骤三：OpencodeApiClient 添加 revert/unrevert API

### 改动

```kotlin
suspend fun revertSession(sessionID: String, messageID: String, partID: String? = null, directory: String? = null) {
    val body = mutableMapOf<String, String>()
    body["messageID"] = messageID
    partID?.let { body["partID"] = it }
    val params = mutableMapOf<String, String>()
    directory?.let { params["directory"] = it }
    postUnit("/session/$sessionID/revert", body, params)
}

suspend fun unrevertSession(sessionID: String, directory: String? = null) {
    val params = mutableMapOf<String, String>()
    directory?.let { params["directory"] = it }
    postUnit("/session/$sessionID/unrevert", emptyMap<String, String>(), params)
}
```

注意：revert/unrevert 返回 `Session.Info`，但 Android 当前不需要解析返回值（revert 状态通过 SSE `session.updated` 事件 + 增量同步获取），所以用 `postUnit` 即可。

---

## 步骤四：SessionRepository 添加 revert/unrevert

### 接口

```kotlin
interface SessionRepository {
    ...,
    suspend fun revertSession(sessionID: String, messageID: String, partID: String? = null, directory: String? = null)
    suspend fun unrevertSession(sessionID: String, directory: String? = null)
}
```

### 实现

```kotlin
override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) {
    api.revertSession(sessionID, messageID, partID, directory)
}

override suspend fun unrevertSession(sessionID: String, directory: String?) {
    api.unrevertSession(sessionID, directory)
}
```

API 调用后，SSE `session.updated` 事件会自动触发 SessionEventHandler 更新本地 DB。

---

## 步骤五：UI 层改动

### 5.1 消息搜索面板长按菜单添加"回滚至此"

**位置**：`SessionMessageSearchPanel.kt` 的 `DropdownMenu`

**改动**：在现有"复制"菜单项后添加"回滚至此"选项，仅对 `type == "user"` 的消息显示（因为 revert 的语义是"回到这条用户消息之前"，回滚 agent 消息没有意义）。

```kotlin
if (message.type == "user") {
    DropdownMenuItem(
        text = { Text("回滚至此") },
        onClick = {
            onRevertToMessage(message.id)
            contextMenuMessage = null
        },
    )
}
```

需要给 `SessionMessageSearchPanel` 添加 `onRevertToMessage: (messageID: String) -> Unit` 回调。

**为什么只对 user 消息提供回滚**：这不是 UI 层面的限制，而是 opencode 服务端的设计。revert.ts 第 59-64 行的核心逻辑：无论传入什么 messageID（不指定 partID 时），`!partID && lastUser` 条件会强制将 `revert.messageID` 回退到**上一条 user 消息**。即"一轮对话"（user + assistant 回复）是 revert 的最小单位。因此只在 user 消息上提供入口是正确的。

### 5.2 会话详情 TopBar 添加回滚按钮

**位置**：`SessionDetailScreen.kt` 的 TopBar

**行为**：
- 显示一个回滚图标按钮（类似 undo 图标）
- 点击后弹出确认对话框："确定回滚到最后一条消息之前？"
- 确认后调用 `viewModel.revertToLastMessage()`
- 当 session 处于 revert 状态时（`session.revert != null`），按钮切换为"恢复"图标，点击执行 unrevert

### 5.3 Revert 状态指示条

**位置**：聊天输入框上方（参照 Web 的 `SessionRevertDock`）

**行为**：
- 当 `session.revert != null` 时，显示一个紧凑的横条：
  - 文案："已回滚 N 条消息"
  - "恢复"按钮：执行 unrevert
- 点击横条可展开查看被回滚的消息列表（可选，MVP 可不做）

### 5.4 ViewModel 添加方法

```kotlin
fun revertToMessage(sessionID: String, messageID: String) {
    viewModelScope.launch {
        sessionRepository.revertSession(sessionID, messageID, directory = activeDirectory)
    }
}

fun revertToLastMessage(sessionID: String) {
    viewModelScope.launch {
        // 找到最后一条用户消息的 ID
        val messages = sessionMessageRepository.getRecentWindow(sessionID, 100)
        val lastUser = messages.lastOrNull { it.type == "user" }
        if (lastUser != null) {
            sessionRepository.revertSession(sessionID, lastUser.id, directory = activeDirectory)
        }
    }
}

fun unrevert(sessionID: String) {
    viewModelScope.launch {
        sessionRepository.unrevertSession(sessionID, directory = activeDirectory)
    }
}
```

---

## 步骤六：Revert 后的数据同步保障

### 场景分析

| 时机 | 服务端行为 | Android 收到的事件 |
|------|-----------|-------------------|
| 调用 revert API | 设置 session.revert，回滚文件 | `session.updated`（带 revert 字段） |
| revert 状态下发新消息 | cleanup 删除旧消息 → 发新消息 | `message.removed` × N + `message.updated` × N |
| 调用 unrevert API | 清除 session.revert，恢复文件 | `session.updated`（revert 清空） |

### 保障

1. **`session.updated` 事件**：SessionEventHandler 已处理，会更新本地 SessionEntity（步骤二添加 revert 字段后自动生效）
2. **`message.removed` 事件**：步骤一的 EventReplayer 改动解决
3. **增量同步兜底**：即使 SSE 丢失事件，下次增量同步会通过 Bridge events API 拉取到所有事件并重放
4. **initSync 兜底**：冷启动时 initSync 用 `replaceAllForSession` 全量替换，天然支持删除

### 无需额外同步逻辑

revert/unrevert API 调用后不需要手动触发增量同步——SSE 事件会自动驱动，用户下次打开页面时 initSync 也会兜底。

---

## 实现顺序

1. **步骤一**：EventReplayer 支持删除事件（基础设施，前置依赖）
2. **步骤二**：Session 模型添加 revert 状态（数据层准备）
3. **步骤三 + 四**：API + Repository（调用链打通）
4. **步骤五**：UI 层改动（5.4 ViewModel → 5.2 TopBar 按钮 → 5.3 状态指示 → 5.1 搜索面板菜单）
5. **步骤六**：验证同步保障（手动测试）

## 风险点

1. **DB version 递增**：v18→v19 会破坏现有本地数据（fallbackToDestructiveMigration），用户需重新进入会话触发同步
2. **message.part.removed 处理**：简化为"从 content 中移除 part"可能不够精确——如果 assistant 消息的 content 数组很长，仅移除一个 part 后剩余内容可能语义不完整。但这是 opencode 服务端的 cleanup 策略决定的，客户端只需忠实反映
3. **并发安全**：revert API 调用期间如果 SSE 正在推送事件，可能出现竞态。但现有架构已通过 Room 事务和 Flow 保证最终一致性
