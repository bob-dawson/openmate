# 步骤一：EventReplayer 支持删除事件

## 背景

revert cleanup 会产生 `message.removed` 和 `message.part.removed` 事件。当前 EventReplayer 完全忽略这两个事件，导致 revert 后旧消息在 Android DB 中不会被删除。

## 事件格式

| 事件类型 | 数据 |
|----------|------|
| `message.removed` | `{ sessionID, messageID }` |
| `message.part.removed` | `{ sessionID, messageID, partID }` |

存入 opencode DB 时带版本后缀（如 `message.removed.1`），EventReplayer 已用 `event.type.substringBeforeLast(".")` 去版本，匹配时用 `message.removed`。

## 改动清单

### 1. ReplayChange 添加 Delete

文件：`core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt`

```kotlin
sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(...) : ReplayChange()
    data class Delete(val id: String) : ReplayChange()  // 新增
}
```

### 2. EventReplayer.processEvent 添加两个分支

文件：`core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt`

在 `when(eventType)` 中添加：

**message.removed**：
- 从 `props["messageID"]` 取 messageId
- 产出 `ReplayChange.Delete(messageId)`

**message.part.removed**：
- 从 props 取 messageId 和 partID
- 通过 loader 加载该消息 entity
- 解析 content 数组，过滤掉匹配 partID 的项
- 如果过滤后 content 为空 → `ReplayChange.Delete(messageId)`
- 否则 → `ReplayChange.Update(messageId, type, 更新后的data, timeUpdated)`

### 3. SessionMessageRepositoryImpl 增量同步处理 Delete

文件：`core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`

在 `coalesced` 合并循环中添加 Delete 的 key 逻辑：
- Delete 的 key 是 `change.id`
- 如果前面已有 Insert 同 key → 移除该 Insert（插入又删除等于无操作）
- 如果前面已有 Update 同 key → 替换为 Delete（更新后删除等于只删）

在 `withTransaction` 写入循环中添加：
```kotlin
is ReplayChange.Delete -> {
    db.sessionMessageDao().delete(change.id)
    batchChanges += SessionMessageSyncChange.Remove(change.id)
    allAppliedChanges += SessionMessageSyncChange.Remove(change.id)
}
```

注：`SessionMessageSyncChange.Remove` 和 `SessionMessageDao.delete()` 已存在，无需新增。

### 4. 单元测试

文件：`core/data/src/test/java/com/openmate/core/data/sync/EventReplayerTest.kt`

添加测试用例：
- `replay_messageRemoved_deletesMessage`：发送 `message.removed` 事件 → 产出 `ReplayChange.Delete`
- `replay_messagePartRemoved_removesPartFromContent`：发送 `message.part.removed` → 产出 Update，content 中对应 part 被移除
- `replay_messagePartRemoved_deletesMessageIfContentEmpty`：移除最后一个 part → 产出 Delete

## 不改动的部分

- Bridge：`message.removed` / `message.part.removed` 数据很小（只有 ID），`truncate_event` 的 `_ => data.clone()` 兜底即可
- SessionMessageSyncChange：`Remove` 已存在
- SessionMessageDao：`delete(id)` 已存在

## 验证方式

1. 单元测试通过
2. 在 TUI 中对某会话执行 revert → 发新消息触发 cleanup → Android 增量同步后 DB 中对应消息应被删除
3. 用 `analyze_android_db.py` 确认消息数量变化
