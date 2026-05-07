# 步骤 5：UI 适配 — 三级展示 + SessionMessage 渲染

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 PartRenderer 中新增 SessionMessage 新模型的渲染支持，实现三级展示模型。

**Architecture:** 现有 PartRenderer（925行）基于旧模型 Message + Part 渲染。新增 SessionMessageRenderer 负责新模型渲染，两者共存。ChatViewModel 根据数据源选择渲染器。

**Tech Stack:** Jetpack Compose, Material 3, Kotlin

---

## Files

- Create: `feature/session/src/main/java/com/openmate/feature/session/SessionMessageRenderer.kt`
- Modify: `feature/session/src/main/java/com/openmate/feature/session/ChatViewModel.kt`
- Modify: `feature/session/src/main/java/com/openmate/feature/session/PartRenderer.kt`

---

## Task 1: Create SessionMessageRenderer

**Files:**
- Create: `feature/session/src/main/java/com/openmate/feature/session/SessionMessageRenderer.kt`

- [ ] **Step 1: Create the renderer**

将 SessionMessage 域模型映射为 Compose UI 组件。参考现有 PartRenderer 的 DisplayItem 模式，但简化为新模型结构。

```kotlin
package com.openmate.feature.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openmate.core.domain.model.AssistantContent
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.ToolState

object SessionMessageRenderer {

    @Composable
    fun Render(
        message: SessionMessage,
        level: DisplayLevel = DisplayLevel.SUMMARY,
        onExpand: ((messageID: String) -> Unit)? = null,
        onFetchFull: ((sessionID: String, messageID: String) -> Unit)? = null,
        modifier: Modifier = Modifier,
    ) {
        when (message) {
            is SessionMessage.User -> RenderUserMessage(message, level, modifier)
            is SessionMessage.Assistant -> RenderAssistantMessage(message, level, onExpand, onFetchFull, modifier)
            is SessionMessage.Shell -> RenderShellMessage(message, level, onExpand, modifier)
            is SessionMessage.Compaction -> RenderCompactionMessage(message, level, onExpand, modifier)
            is SessionMessage.Synthetic -> RenderSyntheticMessage(message, level, modifier)
            is SessionMessage.AgentSwitched -> RenderAgentSwitchedMessage(message, modifier)
            is SessionMessage.ModelSwitched -> RenderModelSwitchedMessage(message, modifier)
        }
    }
}

enum class DisplayLevel {
    SUMMARY,      // 一级：折叠摘要
    EXPANDED,     // 二级：本地完整 data
    FULL_CONTENT, // 三级：回源完整内容
}
```

各消息类型的渲染策略：

| 类型 | 一级（折叠） | 二级（展开） | 三级（回源） |
|------|------------|------------|------------|
| `user` | 文本摘要（前100字）+ 文件名列表 | 完整文本 + 文件名 | 同二级 |
| `assistant` | text 内容 + tool 计数 | 完整 text + tool 摘要 | 完整 tool 输出 |
| `shell` | `command` + exit code | command + 截断 output | 完整 output |
| `compaction` | 前3行摘要 | 截断后全文 | 完整 summary |
| `synthetic` | 文本摘要 | 完整文本 | 同二级 |
| `agent-switched` | agent 名称 | 同一级 | 同一级 |
| `model-switched` | model 名称 | 同一级 | 同一级 |

**详细 Compose 实现在编码阶段完成，这里只定义接口和策略。**

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Update ChatViewModel for dual data source

**Files:**
- Modify: `feature/session/src/main/java/com/openmate/feature/session/ChatViewModel.kt`

- [ ] **Step 1: Add SessionMessage observation**

在 ChatViewModel 中新增对 SessionMessage 的观察，与旧 Message 观察共存。

```kotlin
// 新增字段
private val _sessionMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
val sessionMessages: StateFlow<List<SessionMessage>> = _sessionMessages.asStateFlow()

private val _useNewModel = MutableStateFlow(false)
val useNewModel: StateFlow<Boolean> = _useNewModel.asStateFlow()

// 初始化时同时观察两套数据
init {
    // ... existing code ...
    viewModelScope.launch {
        eventSyncRepository.observeSessionMessages(sessionID).collect { messages ->
            _sessionMessages.value = messages
            // 如果有新模型数据，自动切换
            if (messages.isNotEmpty() && !_useNewModel.value) {
                _useNewModel.value = true
            }
        }
    }
}
```

**数据源选择策略：**
- 优先使用新模型数据（SessionMessage）
- 如果新模型无数据（旧会话），回退到旧模型（Message + Part）
- 可手动切换数据源（调试用）

- [ ] **Step 2: Add full content fetch**

```kotlin
fun fetchFullContent(messageID: String) {
    viewModelScope.launch {
        eventSyncRepository.fetchFullContent(sessionID, messageID)
    }
}
```

- [ ] **Step 3: Build and verify**

Run: `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 3: Update ChatScreen for dual rendering

**Files:**
- Modify: `feature/session/src/main/java/com/openmate/feature/session/ChatScreen.kt` (or equivalent)

- [ ] **Step 1: Conditional rendering path**

在消息列表中根据 `useNewModel` 选择渲染路径：

```kotlin
// 在消息列表的 LazyColumn item 中
if (useNewModel) {
    val msg = sessionMessages.find { it.id == messageID }
    if (msg != null) {
        SessionMessageRenderer.Render(
            message = msg,
            level = displayLevel,
            onExpand = { /* toggle level */ },
            onFetchFull = { sid, mid -> viewModel.fetchFullContent(mid) },
        )
    }
} else {
    // 现有 PartRenderer 渲染
    PartRenderer.Render(part, ...)
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 4: Implement three-level display for tool content

**Files:**
- Modify: `feature/session/src/main/java/com/openmate/feature/session/SessionMessageRenderer.kt`

- [ ] **Step 1: Implement tool content three-level rendering**

Assistant 消息中的 tool content 是三级展示的重点：

```
一级（SUMMARY）：
  [工具图标] bash: npm test
  ↳ 退出码: 0

二级（EXPANDED）：
  [工具图标] bash: npm test
  ↳ 输出:
  > test-suite@1.0.0 test
  > jest --coverage
  ... [142 lines truncated] ...
  Test Suites: 5 passed, 5 total
  Tests:       23 passed, 23 total

三级（FULL_CONTENT）：
  [工具图标] bash: npm test
  ↳ [完整输出，可滚动]
  （从回源 API 获取或本地缓存）
```

每个工具类型有定制的摘要渲染：
- **bash**: command + exit code
- **read/write/edit/apply_patch**: filePath
- **glob**: pattern + count
- **grep**: pattern + matches
- **task**: description + status
- **webfetch/websearch**: url/query
- **其他**: tool name

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Design Notes

### 新旧渲染器共存策略

**方案：独立渲染器 + ViewModel 切换，而非修改 PartRenderer。**

理由：
1. PartRenderer 已有 925 行，修改风险大
2. 新旧模型数据结构完全不同，强行统一增加复杂度
3. 过渡期结束后可删除旧渲染器

### 三级展示的交互设计

- **一级→二级**：点击消息展开（本地 data，无网络请求）
- **二级→三级**：点击"查看完整内容"按钮（触发回源请求，带 loading 状态）
- 三级内容缓存到 `SessionMessageFullContentEntity`，再次查看无需网络

### 流式更新的处理

当前 SSE 流式更新（`message.part.delta`）仍走旧模型。新模型的 `session.next.*.delta` 暂不处理流式更新，等用户看到的是 `ended` 事件后的完整内容。

如果未来需要新模型的流式更新：
1. 在 SessionNextEventHandler 中处理 delta 事件
2. 更新 SessionMessageEntity.data 中的 content 项
3. Compose 观察 Flow 自动刷新

---

## Verification

- [ ] `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] 新模型消息能正确渲染 7 种类型
- [ ] 三级展示交互正常（折叠→展开→回源）
- [ ] 旧模型消息渲染不受影响
