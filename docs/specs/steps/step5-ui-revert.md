# 步骤五：UI 层改动

## 前置依赖

- 步骤一（删除事件）完成
- 步骤二（revert 状态模型）完成
- 步骤三+四（API+Repository 调用链）完成

## 改动清单

### 5.1 ViewModel 添加 revert/unrevert 方法

文件：`feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`

```kotlin
fun revertToMessage(sessionID: String, messageID: String) {
    viewModelScope.launch {
        runCatching {
            sessionRepository.revertSession(sessionID, messageID, directory = activeDirectory)
        }.onSuccess {
            _revertedPrompt.value = extractPromptFromMessage(messageID)
        }.onFailure { e ->
            Log.e(TAG, "revert failed", e)
            _errorMessage.emit("回滚失败: ${e.message}")
        }
    }
}

fun revertToLastMessage(sessionID: String) {
    viewModelScope.launch {
        val messages = sessionMessageRepository.getRecentWindow(sessionID, 100)
        val lastUser = messages.lastOrNull { it.type == "user" }
        if (lastUser != null) {
            runCatching {
                sessionRepository.revertSession(sessionID, lastUser.id, directory = activeDirectory)
            }.onSuccess {
                _revertedPrompt.value = extractPromptFromMessage(lastUser.id)
            }.onFailure { e ->
                Log.e(TAG, "revert failed", e)
                _errorMessage.emit("回滚失败: ${e.message}")
            }
        }
    }
}

fun clearRevertedPrompt() {
    _revertedPrompt.value = null
}

private fun extractPromptFromMessage(messageID: String): String? {
    val db = dbProvider.getActive()
    val entity = db.sessionMessageDao().getById(messageID) ?: return null
    val data = runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return null
    return data["text"]?.jsonPrimitive?.contentOrNull
}
```

**关键行为**：revert 成功后，将被回滚的用户消息文本填入输入框（`_revertedPrompt`）。这与 Web/TUI 行为一致——revert 的语义是"重新来过"，所以把原始输入还给用户，让用户修改后重新发送。

SessionDetailScreen 中输入框组件需要观察 `_revertedPrompt`，当其非 null 时填入文本并清空状态。

### 5.2 TopBar 添加回滚/恢复按钮

文件：`feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`

在 TopBar 的操作按钮区域添加：
- **正常状态**：显示回滚图标按钮（类似 undo），点击弹出确认对话框后执行 `revertToLastMessage`
- **revert 状态**（`session.revert != null`）：按钮切换为恢复图标，点击弹出确认后执行 `unrevert`

确认对话框文案：
- 回滚："确定回滚到上一条消息？此操作将撤销之后的所有对话和文件变更。"
- 恢复："确定恢复被回滚的消息？文件变更也会恢复。"

### 5.3 Revert 状态指示条

文件：新建或复用现有组件

位置：聊天输入框上方（类似 Web 的 `SessionRevertDock`）

当 `session.revert != null` 时显示一个紧凑横条：
- 文案："已回滚消息"
- "恢复"按钮：执行 `unrevert`
- 背景色用轻微的 warning 色调区分

### 5.4 主聊天界面用户消息长按菜单（复制 + 回滚）

文件：`feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt`

当前 `UserMessageItem` 没有长按交互。需要：

1. 给 `UserMessageItem` 添加参数：
   - `messageID: String` — 用于回滚
   - `onRevertToMessage: (messageID: String) -> Unit` — 回滚回调

2. 将 `MessageBubble` 包裹在 `combinedClickable` 中，添加长按弹出 `DropdownMenu`：
   - **复制**：复制消息文本到剪贴板（当前主聊天界面没有复制功能，一并补上）
   - **回滚至此**：调用 `onRevertToMessage(messageID)`

3. 回调从 `SessionDetailScreen` 经 `SessionMessageRenderer` 传递到 `UserMessageItem`

### 5.5 消息搜索面板长按菜单添加"回滚至此"

文件：`feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageSearchPanel.kt`

在现有"复制"菜单项后添加"回滚至此"，仅对 `type == "user"` 的消息显示：

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

给 `SessionMessageSearchPanel` 添加回调参数：
```kotlin
onRevertToMessage: (messageID: String) -> Unit = {}
```

调用方（SessionDetailScreen）传入：
```kotlin
onRevertToMessage = { messageID ->
    viewModel.revertToMessage(sessionID, messageID)
}
```

### 5.6 Revert 状态下消息过滤

**核心问题**：revert 后消息并未立即删除（直到用户发新 prompt 才 cleanup），但 UI 上应隐藏被回滚的消息。

**MessageID 格式**：`msg_` + 12 hex 时间戳 + 14 random base62，ascending 模式生成，**字符串字典序等价于时间序**。因此 `id < revert.messageID` 的字符串比较可以正确判断消息是否在回滚点之前。

文件：`feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`

在消息列表 Flow 中添加过滤逻辑：

```kotlin
// observe messages 并在 revert 状态下过滤
val filteredMessages = combine(
    sessionMessageRepository.observeMessages(sessionId),
    sessionRepository.observeSession(sessionId),
) { messages, session ->
    val revertMsgId = session?.revert?.messageID
    if (revertMsgId != null) {
        messages.filter { it.id < revertMsgId }
    } else {
        messages
    }
}
```

注意：被过滤掉的不仅是 user 消息，还包括回滚点之后的所有 assistant/shell/compaction 消息。这与 Web 行为一致——Web 端 `userMessages().filter(m => m.id < revert)` 过滤 user 消息，assistant 消息按 user 消息的 parentID 关联显示。

Android 当前的扁平消息模型没有 parentID 关联，简单按 `id < revert.messageID` 过滤即可——因为 ascending ID 保证消息 ID 按时间递增，回滚点之后的所有消息（无论 user 还是 assistant）ID 都更大。

### 5.6 字符串资源

文件：`feature/session/src/main/res/values/strings.xml`

添加：
- 回滚按钮相关文案
- 确认对话框文案
- revert 状态指示条文案

## 验证方式

1. 编译通过
2. 手动测试：
   - 在搜索面板长按 user 消息 → "回滚至此" → 确认 → 消息消失，显示 revert 指示条
   - 点击恢复按钮 → 确认 → 消息恢复
   - TopBar 回滚按钮 → 确认 → 同上
   - revert 后发送新消息 → 旧消息被 cleanup 删除
3. 验证 SSE 事件和增量同步正确处理 revert/unrevert 状态变化
