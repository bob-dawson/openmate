# Step 06: UI 适配

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 改造 SessionDetailScreen 和相关 UI 组件，从旧的 Message+Part 模型切换到新的 SessionMessage 模型。删除旧的 PartRenderer，新建 SessionMessageRenderer。

**Architecture:** UI 层直接解析 `SessionMessageEntity.data` JSON 字符串，按 `type` 字段分发渲染。支持 7 种消息类型。三级展示：折叠摘要 → 本地 data → 回源弹窗。

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`
**TUI Reference:** `D:\github\opencode\packages\opencode\src\cli\cmd\tui\feature-plugins\system\session-v2.tsx`

---

## File Structure

```
feature/session/src/main/java/com/openmate/feature/session/
├── SessionDetailViewModel.kt     — 重写：使用新 Repository + SSE
├── SessionDetailScreen.kt        — 改造：新消息列表
└── component/
    ├── SessionMessageRenderer.kt  — 新增：统一消息渲染入口
    ├── UserMessageItem.kt         — 新增
    ├── AssistantMessageItem.kt    — 新增
    ├── ToolCallItem.kt            — 新增（bash/read/write/edit/glob/grep 等）
    ├── ModelSwitchedItem.kt       — 新增
    ├── AgentSwitchedItem.kt       — 新增
    ├── CompactionItem.kt          — 新增
    ├── ShellMessageItem.kt        — 新增
    └── FullContentDialog.kt       — 新增：回源弹窗
```

旧的 `PartRenderer.kt`、`MessageItem.kt` 等删除。

---

## Task 1: 重写 SessionDetailViewModel

**Files:**
- Modify: `feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`

- [ ] **Step 1: 替换依赖**

旧的：
- `MessageRepository`（旧接口）
- `PartDao`/`MessageDao`
- 15 秒轮询 `syncMessages()`

新的：
- `SessionMessageRepository`（新接口）
- `SyncSseClient` + `SyncSseHandler`

```kotlin
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionMessageRepository: SessionMessageRepository,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
    // ... 其他不变（sessionRepository, permission, question 等）
) : ViewModel() {

    private val _messages = MutableStateFlow<List<SessionMessageEntity>>(emptyList())
    val messages: StateFlow<List<SessionMessageEntity>> = _messages.asStateFlow()

    fun loadSession(sessionID: String) {
        viewModelScope.launch {
            // 1. 初始化同步（拉快照）
            sessionMessageRepository.initSync(sessionID)
            // 2. 观察 Room 数据变化
            sessionMessageRepository.observeMessages(sessionID)
                .collect { _messages.value = it }
        }
        // 3. 启动 SSE 监听
        syncSseClient.connect(bridgeBaseUrl)
        syncSseHandler.start()
    }

    override fun onCleared() {
        super.onCleared()
        syncSseClient.disconnect()
    }

    fun fetchFullContent(sessionId: String, messageId: String) {
        viewModelScope.launch {
            sessionMessageRepository.fetchFullMessage(sessionId, messageId)
        }
    }
}
```

- [ ] **Step 2: Commit**

```
git add feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt
git commit -m "feat(session): rewrite ViewModel with new sync engine"
```

---

## Task 2: SessionMessageRenderer

**Files:**
- Create: `feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt`
- Create 各子组件文件

- [ ] **Step 1: 创建统一渲染入口**

```kotlin
@Composable
fun SessionMessageRenderer(
    entity: SessionMessageEntity,
    onFullContentRequest: (messageId: String) -> Unit,
) {
    val dataJson = remember(entity.data) {
        runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull() ?: return
    }

    when (entity.type) {
        "user" -> UserMessageItem(dataJson)
        "assistant" -> AssistantMessageItem(dataJson, onFullContentRequest)
        "model-switched" -> ModelSwitchedItem(dataJson)
        "agent-switched" -> AgentSwitchedItem(dataJson)
        "compaction" -> CompactionItem(dataJson)
        "shell" -> ShellMessageItem(dataJson)
        "synthetic" -> { /* 不渲染或小字显示 */ }
        else -> UnknownMessageItem(entity)
    }
}
```

- [ ] **Step 2: 实现各消息类型组件**

每个组件从 `JsonObject` 中提取字段渲染。参考 TUI `session-v2.tsx` 中的渲染逻辑（行 147-1100）。

**UserMessageItem**: 显示 text，附件 tags
**AssistantMessageItem**: 遍历 content[] 渲染 text/reasoning/tool
**ToolCallItem**: 按 name 分发（bash→命令+输出, read→文件名, edit→diff 统计, glob→匹配数, etc.）
**ModelSwitchedItem**: 一行文字 "切换模型到 provider/model"
**AgentSwitchedItem**: 一行文字 "切换 Agent 到 xxx"
**CompactionItem**: 可折叠的 summary
**ShellMessageItem**: 命令 + 输出

每个组件的具体 Compose 实现此处不展开（与现有 UI 风格一致），实现时参考现有 `PartRenderer.kt` 的布局和主题。

- [ ] **Step 3: 删除旧渲染组件**

删除：
- `PartRenderer.kt`
- 旧的 `MessageItem.kt`
- 其他引用旧 Part 类型的组件

- [ ] **Step 4: 更新 SessionDetailScreen**

将消息列表改为：

```kotlin
LazyColumn {
    items(messages, key = { it.id }) { entity ->
        SessionMessageRenderer(
            entity = entity,
            onFullContentRequest = { messageId ->
                viewModel.fetchFullContent(sessionID, messageId)
            },
        )
    }
}
```

- [ ] **Step 5: 验证编译 + 运行**

Run: `./gradlew :feature:session:compileDebugKotlin`
Expected: 编译成功

Run: `./gradlew assembleDebug`
Expected: 构建成功

安装到设备，测试打开一个有数据的会话。

- [ ] **Step 6: Commit**

```
git add -A feature/session/
git commit -m "feat(session): implement SessionMessageRenderer and migrate UI to new model"
```

---

## Task 3: 端到端验证

- [ ] **Step 1: 安装新版本 app**

确保 Bridge 已运行且 opencode 已启动。

- [ ] **Step 2: 打开有数据的会话**

验证：
- 消息列表正常显示（user、assistant、model-switched、agent-switched、compaction）
- Tool call 渲染正常（bash 命令+输出、read 文件名、edit 统计等）
- 截断生效（大段代码/输出被截断）

- [ ] **Step 3: 触发新消息**

在 TUI 或其他客户端发送消息，验证：
- SSE 通知到达
- 增量同步触发
- 新消息出现在 app 中

- [ ] **Step 4: 测试回源**

点击截断的消息，触发 full API 调用，验证完整内容显示。

- [ ] **Step 5: 测试离线**

关闭网络，重新打开 app，验证已缓存的消息仍可显示。
