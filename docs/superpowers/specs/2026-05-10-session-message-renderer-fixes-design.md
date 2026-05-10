# SessionMessageRenderer 功能补全设计

## 问题

`SessionMessageRenderer`（新渲染路径）相比老的 `PartRenderer` + `PartColumn` 路径，缺失了三项关键功能：

1. **Agent 轨迹不连贯**：step-start/step-finish/snapshot/agent/subtask 等内容类型被静默丢弃，用户看不到空缺时间段 agent 在做什么
2. **子代理会话无法点击**：tool content 的 `metadata`/`callID` 未提取，running 状态的 task 工具没用 `TaskToolLine` 渲染
3. **Question/Permission 卡片不显示**：question 工具类型没有特殊处理，`hasVisible` 过滤也会跳过仅含 question/permission 的消息；当前只靠模态弹窗显示第一条

## 修改范围

仅修改 `SessionMessageRenderer.kt` 和 `SessionDetailScreen.kt`，复用 `PartRenderer.kt` 中已有的 `QuestionCard`/`PermissionCard`/`TaskToolLine`/`parseQuestionArgs` 等组件和函数。

---

## 1. Agent 轨迹占位行

### 方案

对 assistant 消息的 content 数组中被过滤的类型，显示折叠摘要占位行：

| 内容类型 | 渲染方式 |
|---------|---------|
| `step-start` | 开始一个 step 分组，记录起始时间 |
| `step-finish` | 结束 step 分组，显示摘要行 `⚡ Step · {耗时} · {工具数} 工具调用`，点击展开可看工具列表 |
| `snapshot` | 忽略（step 分组内） |
| `agent` | 显示 `▸ agent: {name}` |
| `subtask` | 显示 `▸ subtask: {description}` |
| `compaction` | 显示 `▸ compaction` |
| `retry` | 显示 `▸ retry #{attempt}: {error}` |

### Step 分组逻辑

```
content 数组遍历：
  step-start → stepDepth++
  step-finish → stepDepth--
  当 stepDepth == 0 时（最外层 step 结束），渲染摘要行

摘要行内容：
  - 已完成 step：耗时 = step-finish.time.completed - step-start.time.started（均为 PC 时间，同源计算无漂移）
  - 执行中 step：用锚点相对时间法（见下文），每秒刷新
  - 工具数：统计 step 内 name!="question" 的 tool 条目数
  - 点击展开：显示 step 内的所有工具行（简化版，只显示工具名+状态）
```

### 执行中 Step 的实时耗时 — 锚点相对时间法

PC 和手机系统时钟可能不一致，不能直接用 `手机当前时间 - PC开始时间` 计算耗时。

**方案**：首次渲染 running step 时记录两个锚点：

| 值 | 来源 | 用途 |
|----|------|------|
| `pcStartTime` | step-start 数据中的 `time.started`（PC 时间） | 仅用于显示开始时刻 `HH:mm:ss` |
| `phoneAnchor` | `SystemClock.elapsedRealtime()`（手机单调时钟） | 耗时计算基准 |

**显示的实时耗时** = `SystemClock.elapsedRealtime() - phoneAnchor`，用 `LaunchedEffect(Unit) { while(true) { delay(1000); ... } }` 每秒刷新。

优势：
- 耗时计算完全基于手机单调时钟，不受 PC/手机时钟偏差影响
- `SystemClock.elapsedRealtime()` 不受系统时间手动调整影响（单调递增）
- 已完成的 step 仍然用 PC 时间差（`completedAt - startedAt`），两者同源无漂移

### `hasVisible` 更新

`hasVisible` 检查增加以下类型：
- `"question"` → true（question 工具始终可见）
- `"step-start"` / `"step-finish"` → true（step 摘要行可见）
- `"agent"` / `"subtask"` → true
- `"compaction"` / `"retry"` → true

---

## 2. 子代理导航修复

### 修改点

在 `AssistantMessageItem` 解析 tool content 时：

1. 提取 `callID`：从 `obj["callID"]` 或 `state["callID"]` 获取
2. 提取 `metadata`：从 `state["metadata"]` 获取（JsonObject）
3. 传入 `DisplayItem.ToolItem` 的 `callID` 和 `metadata` 字段

### Running 状态的 task 工具

当 `name == "task"` 时，**所有状态**都用 `TaskToolLine` 渲染（pending/running/completed/error），不再走 `RunningToolLine`/`PendingToolLine`。

需要给 `SessionMessageRenderer` 和 `AssistantMessageItem` 传入 `pendingQuestions`/`pendingPermissions` 和回调函数，以便 `TaskToolLine` 能显示子代理的权限/问题卡片。

---

## 3. Question/Permission 内联卡片

### 修改点

在 `AssistantMessageItem` 的 tool 处理分支中：

**Question 工具**（`name == "question"` 且 `status == "running"`）：
- 调用 `parseQuestionArgs(input)` 解析问题列表
- 用 `callID` 匹配 `pendingQuestions` 找到对应的 `QuestionRequest`
- 渲染 `QuestionCard`（复用 `PartRenderer` 中的），传入 reply/reject 回调

**Permission 匹配**（`status == "pending"` 或 `status == "running"`）：
- 用 `callID` 匹配 `pendingPermissions` 找到对应的 `PermissionRequest`
- 渲染 `PermissionCard`（复用 `PartRenderer` 中的），传入 allow/deny 回调

### 参数传递链

```
SessionDetailScreen
  → SessionMessageRenderer（新增参数：pendingQuestions, pendingPermissions, 回调函数）
    → AssistantMessageItem（新增参数）
      → TaskToolLine / QuestionCard / PermissionCard
```

### 移除模态弹窗

内联卡片实现后，移除 `SessionDetailScreen` 底部的 `QuestionDialog`/`PermissionDialog` 模态弹窗。如果所有 question/permission 都能在内联卡片中处理，模态弹窗就是多余的。

---

## 4. `parseQuestionArgs` 提取为公共函数

当前 `parseQuestionArgs` 是 `PartRenderer.kt` 的 `private` 函数。需要改为 `internal` 以便 `SessionMessageRenderer` 调用。

---

## 实现步骤

1. `PartRenderer.kt`：`parseQuestionArgs` 改为 `internal`
2. `SessionMessageRenderer.kt`：
   - 更新 `SessionMessageRenderer` 签名，新增 pendingQuestions/pendingPermissions/回调参数
   - 更新 `hasVisible` 检查，增加 question/step-start/step-finish/agent/subtask 类型
   - 重写 `AssistantMessageItem`：
     - 新增参数：pendingQuestions/pendingPermissions/回调
     - 提取 tool content 的 callID/metadata
     - question 工具：用 QuestionCard 渲染
     - permission 匹配：用 PermissionCard 渲染
     - task 工具：所有状态用 TaskToolLine 渲染
     - step-start/step-finish：step 分组 + 摘要行
     - agent/subtask/compaction/retry：文本行
3. `SessionDetailScreen.kt`：
   - 传递 pendingQuestions/pendingPermissions/回调给 SessionMessageRenderer
   - 移除底部模态弹窗代码
