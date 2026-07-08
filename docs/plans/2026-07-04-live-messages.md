# 实时消息显示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户可以开启实时消息显示，通过 SSE 流式看到 reasoning/text/compaction 等中间过程，完成后自动替换为增量同步的完整消息。

**Architecture:** 双通道设计——DB 消息 + SSE 临时消息合并渲染。开启设置后，SSE 连接带 `?live=1` 参数，Bridge 跳过 delta/part 事件过滤。Android 端维护一个 `liveParts` 内存状态，追加在消息列表末尾实时显示；增量同步拉到完整消息后，对应临时消息自动移除。

**Tech Stack:** Rust (Bridge axum SSE filter) / Kotlin + Compose (Android) / SharedPreferences (设置存储)

---

## 修改范围

### Bridge 端（3 文件）

| 文件 | 改动 |
|------|------|
| `opencode-bridge/src/events/router.rs` | `events_sse` handler 接受 `?live` query 参数，传入 stream 构造 |
| `opencode-bridge/src/events/source.rs` | `create_events_stream` 接受 `live: bool` 参数，控制 broadcast 订阅行为 |
| `opencode-bridge/src/events/filter.rs` | 新增 `filter_live_event()` 函数，live 模式下保留 `message.part.delta`/`message.part.updated`/`message.part.removed` 等事件 |

### Android 端（8 文件）

| 文件 | 改动 |
|------|------|
| `feature/settings/.../SettingsViewModel.kt` | 新增 `liveMessages` 设置项（SharedPreferences key `live_messages`，默认 false） |
| `feature/session/.../WorkspaceListScreen.kt` | 设置页 Display section 新增「实时消息」开关 |
| `core/network/.../SyncSseClient.kt` | `connect()` 接受 `liveMessages: Boolean`，开启时 URL 加 `?live=1` |
| `app/.../EffectExecutor.kt` | 读取 `liveMessages` 设置，传给 `syncSseClient.connect()` |
| `core/data/.../sse/EventDispatcher.kt` | 新增 `_livePartEvents` SharedFlow，转发 `message.part.delta`/`message.part.updated` 事件 |
| `feature/session/.../SessionDetailViewModel.kt` | 新增 `_liveParts` 临时消息状态，监听 SSE 事件实时更新；增量同步后清理已同步的临时消息 |
| `feature/session/.../SessionDetailScreen.kt` | `displayMessages` 合并 DB 消息 + 临时消息；临时消息使用流式渲染组件 |
| `core/ui/.../StreamingText.kt` | 新增或复用流式文本组件，支持打字机效果 + reasoning 折叠状态切换 |

---

## Task 1: Bridge SSE 支持 `?live` 参数

**Files:**
- Modify: `opencode-bridge/src/events/router.rs`
- Modify: `opencode-bridge/src/events/filter.rs`
- Modify: `opencode-bridge/src/events/source.rs`

- [ ] **Step 1: filter.rs 新增 `filter_live_bridge_event`**

在 `filter.rs` 中新增函数，live 模式下额外保留 `message.part.delta`、`message.part.updated`、`message.part.removed` 事件：

```rust
pub fn filter_live_bridge_event(event: &Value) -> Option<Value> {
    let event_type = event.get("type")?.as_str()?;
    if matches!(
        event_type,
        "message.part.delta" | "message.part.updated" | "message.part.removed"
    ) {
        return Some(event.clone());
    }
    filter_normalized_event(event)
}
```

- [ ] **Step 2: source.rs `create_events_stream` 接受 `live` 参数**

修改函数签名和内部逻辑，`live=true` 时使用 `filter_live_bridge_event`，否则使用 `filter_normalized_event`：

```rust
pub fn create_events_stream(state: AppState, live: bool) -> impl Stream<Item = Result<Event, Infallible>> {
    let mut receiver = state.event_source.subscribe();
    let filter = if live {
        |event: &Value| filter_live_bridge_event(event)
    } else {
        |event: &Value| filter_normalized_event(event)
    };
    // ... stream 构造中用 filter 替换原来的 filter_normalized_event
}
```

- [ ] **Step 3: router.rs `events_sse` 接受 `?live` query 参数**

```rust
#[derive(Deserialize)]
pub struct EventsQuery {
    live: Option<String>,
}

pub async fn events_sse(
    State(state): State<AppState>,
    Query(query): Query<EventsQuery>,
) -> impl IntoResponse {
    let live = query.live.as_deref() == Some("1");
    let stream = create_events_stream(state, live);
    Sse::new(stream).keep_alive(KeepAlive::default())
}
```

- [ ] **Step 4: 编译验证**

Run: `cd D:\openmate\opencode-bridge && cargo build --release`
Expected: 编译成功

- [ ] **Step 5: 测试验证**

Run: `cd D:\openmate\opencode-bridge && cargo test`
Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add opencode-bridge/src/events/router.rs opencode-bridge/src/events/filter.rs opencode-bridge/src/events/source.rs
git commit -m "feat(bridge): SSE /api/bridge/events 支持 ?live=1 参数转发实时消息事件"
```

---

## Task 2: Android 设置项——实时消息开关

**Files:**
- Modify: `android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml` (添加字符串资源)

- [ ] **Step 1: SettingsViewModel 新增 liveMessages 设置**

在 `SettingsViewModel.kt` 中：
- 新增 `private const val KEY_LIVE_MESSAGES = "live_messages"`
- 新增 `_liveMessages = MutableStateFlow(prefs.getBoolean(KEY_LIVE_MESSAGES, false))`
- 新增 `val liveMessages: StateFlow<Boolean> = _liveMessages.asStateFlow()`
- 新增 `fun setLiveMessages(enabled: Boolean) { _liveMessages.value = enabled; prefs.edit().putBoolean(KEY_LIVE_MESSAGES, enabled).apply() }`

- [ ] **Step 2: strings.xml 新增字符串**

```xml
<string name="live_messages">实时消息</string>
<string name="live_messages_subtitle">开启后实时显示消息的生成过程（思考、文本输出等），需要 SSE 连接支持</string>
```

- [ ] **Step 3: WorkspaceListScreen 设置页 Display section 新增开关**

在 `compact_mode` 开关之后添加：

```kotlin
val liveMessages by viewModel.liveMessages.collectAsState()
SettingsRow(
    title = stringResource(R.string.live_messages),
    subtitle = stringResource(R.string.live_messages_subtitle),
    showDivider = false,
    trailing = {
        Switch(
            checked = liveMessages,
            onCheckedChange = { viewModel.setLiveMessages(it) },
        )
    },
)
```

- [ ] **Step 4: Gradle 构建验证**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt android/app/src/main/res/values/strings.xml
git commit -m "feat(android): 新增实时消息显示设置开关"
```

---

## Task 3: SyncSseClient 支持 `?live=1` 参数

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/EffectExecutor.kt`

- [ ] **Step 1: SyncSseClient.connect 新增 liveMessages 参数**

修改 `connect` 方法签名，增加 `liveMessages: Boolean = false` 参数：

```kotlin
suspend fun connect(baseUrl: String, forceRestart: Boolean = false, liveMessages: Boolean = false) {
    // ... 在构造 URL 时：
    val url = if (liveMessages) "$baseUrl/api/bridge/events?live=1" else "$baseUrl/api/bridge/events"
    val urlBuilder = Request.Builder().url(url).get()
    // ...
}
```

- [ ] **Step 2: EffectExecutor 读取设置并传参**

在 `EffectExecutor.startSse()` 中，读取 SharedPreferences 的 `live_messages` 值，传给 `syncSseClient.connect()`：

```kotlin
private fun startSse(route: Route) {
    // ... 现有逻辑
    val liveMessages = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean("live_messages", false)
    sseJob = scope.launch {
        syncSseClient.connect(baseUrl, forceRestart = true, liveMessages = liveMessages)
    }
}
```

- [ ] **Step 3: Gradle 构建验证**

同上

- [ ] **Step 4: 提交**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt android/app/src/main/java/com/openmate/app/connection/v2/EffectExecutor.kt
git commit -m "feat(android): SSE 连接支持 ?live=1 参数"
```

---

## Task 4: EventDispatcher 转发实时消息事件

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt`

- [ ] **Step 1: EventDispatcher 新增 livePartEvents SharedFlow**

```kotlin
private val _livePartEvents = MutableSharedFlow<LivePartEvent>(extraBufferCapacity = 64)
val livePartEvents: SharedFlow<LivePartEvent> = _livePartEvents

data class LivePartEvent(
    val sessionId: String,
    val messageId: String,
    val partId: String,
    val type: String,       // "delta" | "updated" | "removed"
    val partType: String?,  // "text" | "reasoning" | "tool" | ...
    val text: String?,      // delta/updated 的文本内容
    val rawProperties: JsonObject,
)
```

- [ ] **Step 2: dispatch 中转发 message.part.* 事件**

在 `dispatch()` 方法中，当事件类型为 `message.part.delta`、`message.part.updated`、`message.part.removed` 时，构造 `LivePartEvent` 并发射：

```kotlin
// 在 when 分支中添加
type.startsWith("message.part.") -> {
    val sessionId = event.properties["sessionID"]?.jsonPrimitive?.content ?: return
    val messageId = event.properties["messageID"]?.jsonPrimitive?.content ?: return
    val partId = event.properties["partID"]?.jsonPrimitive?.content ?: return
    val partType = event.properties["type"]?.jsonPrimitive?.contentOrNull
    val text = event.properties["text"]?.jsonPrimitive?.contentOrNull
    val liveType = when {
        type.endsWith(".delta") -> "delta"
        type.endsWith(".updated") -> "updated"
        type.endsWith(".removed") -> "removed"
        else -> return
    }
    _livePartEvents.tryEmit(LivePartEvent(sessionId, messageId, partId, liveType, partType, text, event.properties))
}
```

- [ ] **Step 3: Gradle 构建验证**

同上

- [ ] **Step 4: 提交**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt
git commit -m "feat(android): EventDispatcher 转发 message.part.* 实时消息事件"
```

---

## Task 5: ViewModel 维护临时消息状态 + 合并渲染

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`

这是核心任务，分为两部分。

### 5a: ViewModel 临时消息状态

- [ ] **Step 1: 定义 LivePart 数据模型**

在 ViewModel 中定义内部数据类：

```kotlin
data class LivePart(
    val partId: String,
    val messageId: String,
    val partType: String,   // "text" | "reasoning" | "tool" | "compaction"
    val text: String,       // 累积的文本内容
    val isComplete: Boolean, // part 是否已完成（收到非 delta 事件或 step-finish）
)
```

- [ ] **Step 2: 新增 `_liveParts` 状态**

```kotlin
private val _liveParts = MutableStateFlow<List<LivePart>>(emptyList())
val liveParts: StateFlow<List<LivePart>> = _liveParts.asStateFlow()
```

- [ ] **Step 3: 监听 SSE 实时事件，更新 `_liveParts`**

在会话加载时启动收集（与 `observeMessageSyncJob` 类似）：

```kotlin
private var livePartsJob: Job? = null

private fun startLivePartsObserver(sessionId: String) {
    livePartsJob?.cancel()
    livePartsJob = viewModelScope.launch(Dispatchers.IO) {
        eventDispatcher.livePartEvents
            .filter { it.sessionId == sessionId }
            .collect { event ->
                val current = _liveParts.value.toMutableList()
                when (event.type) {
                    "delta" -> {
                        val existing = current.find { it.partId == event.partId }
                        if (existing != null) {
                            val idx = current.indexOf(existing)
                            current[idx] = existing.copy(text = existing.text + (event.text ?: ""))
                        } else {
                            current.add(LivePart(event.partId, event.messageId, event.partType ?: "text", event.text ?: "", false))
                        }
                    }
                    "updated" -> {
                        val existing = current.find { it.partId == event.partId }
                        if (existing != null) {
                            val idx = current.indexOf(existing)
                            current[idx] = existing.copy(text = event.text ?: existing.text, isComplete = true)
                        } else {
                            current.add(LivePart(event.partId, event.messageId, event.partType ?: "text", event.text ?: "", true))
                        }
                    }
                    "removed" -> {
                        current.removeAll { it.partId == event.partId }
                    }
                }
                _liveParts.value = current
            }
    }
}
```

- [ ] **Step 4: 增量同步后清理已同步的临时消息**

在 `applySyncResult` 或 `rebuildInitialWindow` 之后，检查哪些 `liveParts` 对应的 messageId 已存在于 DB 消息中，移除这些临时消息：

```kotlin
private fun cleanupSyncedLiveParts() {
    val msgIds = _messages.value.map { it.id }.toSet()
    val remaining = _liveParts.value.filter { it.messageId !in msgIds }
    _liveParts.value = remaining
}
```

在 `applySyncResult` 和 `rebuildInitialWindow` 末尾调用 `cleanupSyncedLiveParts()`。

- [ ] **Step 5: 会话切换时重置**

在 `loadSession` / `onCleared` 中 `livePartsJob?.cancel()` 和 `_liveParts.value = emptyList()`。

### 5b: Screen 合并渲染

- [ ] **Step 6: displayMessages 合并 DB 消息 + 临时消息**

在 `SessionDetailScreen.kt` 中，修改 `displayMessages` 的构造逻辑：

```kotlin
val liveParts by viewModel.liveParts.collectAsState()
val showLive = remember { mutableStateOf(prefs.getBoolean("live_messages", false)) }

val displayMessages = remember(messages, liveParts, queuedMessageIds, isBusy, sessionRevert, showLive.value) {
    // ... 现有 filtered 逻辑
    if (!showLive.value || liveParts.isEmpty()) return@remember filtered
    
    // 将 liveParts 追加到消息列表末尾，转为临时 SessionMessage
    val liveItems = liveParts.map { part ->
        SessionMessage(
            id = "live_${part.partId}",
            sessionId = sessionId,
            type = "live",
            timeCreated = System.currentTimeMillis(),
            timeUpdated = System.currentTimeMillis(),
            data = buildJsonObject {
                put("type", part.partType)
                put("text", part.text)
                put("isComplete", part.isComplete)
                put("messageId", part.messageId)
                put("partId", part.partId)
            },
        )
    }
    filtered + liveItems
}
```

- [ ] **Step 7: SessionMessageRenderer 处理 `live` 类型消息**

在 `SessionMessageRenderer.kt` 的 type 分发中新增：

```kotlin
"live" -> {
    val partType = dataJson?.get("type")?.jsonPrimitive?.contentOrNull ?: "text"
    val text = dataJson?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    val isComplete = dataJson?.get("isComplete")?.jsonPrimitive?.booleanOrNull ?: false
    LivePartMessageItem(
        partType = partType,
        text = text,
        isComplete = isComplete,
        showReasoning = showReasoning,
    )
}
```

- [ ] **Step 8: 新增 LivePartMessageItem Composable**

```kotlin
@Composable
private fun LivePartMessageItem(
    partType: String,
    text: String,
    isComplete: Boolean,
    showReasoning: Boolean,
) {
    when (partType) {
        "reasoning" -> {
            var expanded by remember(text.length) { mutableStateOf(true) }
            if (isComplete && !showReasoning) {
                expanded = false
            }
            // 复用现有 ReasoningPartRenderer 的样式，展开时显示光标动画
            // 完成后自动折叠
        }
        "text" -> {
            // 类似现有 TextPartRenderer，带打字光标
            // 完成后移除光标
        }
        "tool" -> {
            // 简化的工具调用显示
        }
        else -> {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

- [ ] **Step 9: Gradle 构建验证**

同上

- [ ] **Step 10: 提交**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt
git commit -m "feat(android): 临时消息实时显示，DB 消息与 SSE 临时消息合并渲染"
```

---

## Task 6: 端到端验证与细节打磨

**Files:**
- 可能修改上述文件中的细节

- [ ] **Step 1: Bridge 部署并验证 live 模式**

```powershell
cd D:\openmate\opencode-bridge && cargo build --release
powershell -File D:\openmate\scripts\update-bridge.ps1
# 验证：curl http://127.0.0.1:11160/api/bridge/events?live=1 能收到 message.part.delta 事件
```

- [ ] **Step 2: Android 安装到手机，开启实时消息设置**

```powershell
Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'
adb -s 2YE0224419004103 install -d -r D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 3: 验证场景**

- 设置页开启「实时消息」→ SSE 连接带 `?live=1`
- 在 opencode TUI 发送消息 → 手机端实时看到 reasoning 展开 + text 追加
- 增量同步完成后 → 临时消息消失，完整消息出现
- 切后台再回来 → 临时消息丢失不影响，增量同步兜底
- 关闭「实时消息」→ SSE 连接不带参数，行为与之前一致

- [ ] **Step 4: 修复发现的问题并提交**

---

## 注意事项

1. **SSE 事件格式**：opencode 的 `message.part.delta` 事件格式为 `{"type":"message.part.delta","properties":{"sessionID":"...","messageID":"...","partID":"...","text":"追加文本"}}`，Android 端需正确解析
2. **Bridge truncate**：live 模式下的 `message.part.updated` 仍需 truncate（移除大字段），但 `message.part.delta` 只含增量文本不需要 truncate
3. **并发安全**：`_liveParts` 更新在 IO 线程，UI 在 Main 线程，StateFlow 本身线程安全
4. **内存**：临时消息只在会话期间存在，页面离开自动清理
5. **reasoning 折叠时机**：收到 `session.next.reasoning.ended` 或 `step-finish` 时标记 isComplete，之后自动折叠
