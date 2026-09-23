# Android 消息回滚（Revert）功能设计

> 状态：**已按 opencode V2 更新**（原 2026-05-13 V1 方案见文末「V1 → V2 差异」）
> 适用：opencode v2（本机实测 v2.0.15）
> 关联：`docs/design/会话同步设计-v2.md`（revert 删除的增量同步）

## 概述

实现 opencode 的消息回滚功能，与 Web/TUI 保持一致。Revert 是**原地回滚**——在原 session 内删除指定消息之后的所有内容，并撤销文件变更。配套 Unrevert 可恢复。

**不实现 Fork**（对话分叉，共享同一 worktree，无文件隔离，对移动端价值有限）。

## opencode V2 Revert 机制

### API（V2）

| API | 方法 | 路径 | Body | 说明 |
|-----|------|------|------|------|
| Revert（stage） | POST | `/api/session/{sessionID}/revert/stage` | `{ messageID, partID? }` | 仅 **stage**，不删除消息 |
| Revert（commit） | POST | `/api/session/{sessionID}/revert/commit` | 无 | 提交，删除消息并回滚文件 |
| Unrevert | DELETE | `/api/session/{sessionID}/revert` | 无 | 清除 staged revert |

均通过 Bridge proxy 转发到 opencode，Bridge 无需改动。
目录参数使用 `location[directory]`（V2 SDK 规则；revert/unrevert 依赖该参数，仅 query `directory` 可能失败）。

### Staged 语义（关键）

1. **stage 不删除任何消息**：调用 `revert/stage` 后，服务端只在 `session_v2.revert = { messageID, files[] }` 上写入边界，消息仍然存在，只是在 UI 中应被隐藏。
2. **发送新 prompt 会自动 commit**：opencode 在受理下一条 prompt 时自动 commit staged revert → 删除边界及之后的消息（`seq >= boundary.seq`）、回滚文件。
3. **可随时 unrevert**：`DELETE /revert` 清除 staged 状态，消息恢复，文件还原。
4. Android 侧 `revertSession` **只调 stage**；不再调用 commit。commit 由服务端在下次发送时完成。

### 删除时机（服务端）

staged revert 被 commit 时，opencode 的 `prompt.loop` 调用 `revert.cleanup()`：
1. 删除 `messageID` 及之后的所有消息
2. 如有 `partID`，只删除该 part 及之后的部分
3. 触发 `message.removed` / `message.part.removed` 同步事件（V2 下同时推进 `session_message` 的 seq）

> V2 下删除**不**通过 SSE 事件稳定送达（event 表默认不落库），Android 靠增量同步的两阶段删除检测兜底，见 `会话同步设计-v2.md`。

## 边界：为什么以 user 消息为单位

revert 的最小单位是「一轮对话」（user + assistant 回复）。opencode `revert.ts`：不指定 `partID` 时，`!partID && lastUser` 会把 `revert.messageID` 回退到**上一条 user 消息**。因此 UI 只在 `type == "user"` 的消息上提供「回滚至此」入口。

## 数据模型

```kotlin
data class SessionRevert(
    val messageID: String,
    val partID: String? = null,
    val from: String? = null,   // 本地隐藏边界（= messageID）
    val to: String? = null,
)

data class Session(
    ...,
    val revert: SessionRevert? = null,
)
```

**SessionEntity** 持久化四个字段：`revertMessageID / revertPartID / revertFrom / revertTo`，与 domain 双向映射。

**SessionDto → Session 映射**：

```kotlin
revert = revert?.let {
    SessionRevert(messageID = it.messageID ?: "", partID = it.partID, from = it.messageID)
}
```

> 服务端只返回 `messageID`；`from` 取 `messageID`，供 UI 计算隐藏边界（本地 `extractMsgTimestamp(from)` 过滤）。

**关键一致性要求**：`SessionRepositoryImpl.getSession()` 与 `getSessions()` 在从服务端刷新时必须**映射 `revert` 字段到 Room**（否则重开会话会丢失 staged 状态）。`revert` 为 null 时也需写回 null（清除）。

## 调用链改动

| 模块 | 改动 |
|------|------|
| core/domain | `Session` 添加 `revert`；`SessionRepository` 添加 `revertSession`/`unrevertSession`/`updateLocalRevert` |
| core/network | `OpencodeApiClient.revertSession` → `POST /revert/stage`；`unrevertSession` → `DELETE /revert` |
| core/data | `SessionRepositoryImpl` 实现上述三方法；`getSession`/`getSessions` 映射 revert |
| core/database | `SessionEntity` 四字段 + `SessionDao.updateRevertFields(id, messageID, partID, from, to)` |
| core/data/sync | EventReplayer 处理删除事件（V2 下为增量同步两阶段兜底） |
| feature/session | ViewModel `revertToMessage`/`revertToLastMessage`/`unrevert`；UI 菜单项 + staged 指示条 |

### `updateLocalRevert`

`feature:session` 不直接依赖 Room（模块无 Room classpath），故本地 revert 持久化通过仓库方法：

```kotlin
suspend fun updateLocalRevert(sessionID: String, revert: SessionRevert?)
// Impl: dbProvider.getActive().sessionDao().updateRevertFields(...)
```

调用点：
- `revertToMessage` 成功后：写入本地 revert（stage 状态立即可见）
- `sendMessage` 成功（服务端已自动 commit）：清空本地 revert
- `unrevert` 成功后：清空本地 revert

## UI 层

### 消息长按菜单「回滚至此」

`SessionMessageRenderer` / `SessionMessageSearchPanel` 的 `DropdownMenu`，仅对 `type == "user"` 显示。
点击后弹确认对话框（`revert_dialog_title`/`revert_dialog_message`），确认调用 `viewModel.revertToMessage(sessionID, message.id)`。

### Staged 状态指示条

输入框上方显示：

- 文案 `reverted_message`（"Message reverted"）
- `unrevert` 按钮 → `viewModel.unrevert(sessionID)`

`_sessionRevert != null` 时，UI 按 `from` 的时间戳隐藏边界及之后的消息。

### ViewModel 状态恢复

`observeSession` 收集 Room 会话流，采用服务端/本地记录的 `revert`：

- **非空**：`_sessionRevert.value = it`（采用 staged 状态）
- **为 null**：**不主动清空**本地 staged（服务端 payload 可能瞬时缺字段）；清空由显式路径负责：
  - `unrevert` 成功
  - `sendMessage` 成功（自动 commit）
  - `loadSession` 初始化

## 同步保障

| 时机 | 服务端行为 | Android |
|------|-----------|---------|
| `revert/stage` | 写 `session_v2.revert` | 本地置 staged、隐藏消息；增量同步无删除 |
| stage 下发送新 prompt | 自动 commit：删除消息 + 回滚文件 | 增量同步两阶段删除检测删除本地消息；清空本地 staged |
| `DELETE /revert` | 清除 revert、还原文件 | 清空本地 staged；消息恢复显示 |
| 重开会话 | `GET /api/session/:id` 返回 `revert` | `getSession` 映射 → Room → 观察流恢复 staged |

**不依赖 SSE**：revert 删除的准确性由 `会话同步设计-v2.md` 的区间计数门闩 + 精确修复保证；SSE 仅提升实时性。

## V1 → V2 差异

| 项 | V1（2026-05-13 原方案） | V2（当前实现） |
|----|------------------------|----------------|
| revert API | `POST /session/:id/revert`（stage+commit 一步） | `POST /revert/stage` 仅 stage，commit 由下次 prompt 自动触发 |
| unrevert API | `POST /session/:id/unrevert` | `DELETE /session/:id/revert` |
| 删除事件来源 | 依赖 SSE `message.removed` / `message.part.removed` | SSE 不可靠；增量同步两阶段删除检测兜底 |
| SessionRevert | `{ messageID, partID? }` | 增加本地 `from`/`to`（`from = messageID`） |
| 本地 revert 持久化 | 依赖 SSE `session.updated` | `getSession/getSessions` 映射 + `updateLocalRevert` 显式维护 |

## 风险点

1. **目录参数**：revert/unrevert 依赖 `location[directory]`；缺省时可能 404/400。
2. **staged 恢复**：若 `getSession` 不再映射 `revert`，重开会话会丢 stage 横幅（已加一致性要求 + 回归测试）。
3. **并发**：revert 调用期间 SSE/增量同步并发，由 Room 事务 + Flow 保证最终一致。
