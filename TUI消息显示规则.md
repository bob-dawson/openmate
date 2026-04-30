# OpenCode TUI 消息显示规则

> 基于对 `packages/opencode/src/cli/cmd/tui/routes/session/index.tsx` 的源码分析

## 消息类型与渲染

### UserMessage
- 过滤掉 `synthetic=true` 的 text part
- 非合成 text parts 用 `"\n\n"` 拼接，显示在有边框的盒子中
- File parts 显示为 badge + 文件名（badge 颜色根据 MIME 类型）
- Compaction part 显示为水平分割线 `─────── Compaction ───────`

### AssistantMessage
使用 `PART_MAPPING` 分发：
```typescript
const PART_MAPPING = {
  text: TextPart,
  tool: ToolPart,
  reasoning: ReasoningPart,
}
```
**只有 3 种 part 有视觉组件**。其余（step-start, step-finish, snapshot, patch, subtask, retry, agent, compaction）在 assistant message 中**不渲染**。

---

## 各 Part 类型渲染细节

### TextPart
- Markdown 或代码块渲染（受实验性 markdown 标志控制）
- 空文本隐藏

### ReasoningPart
- **默认隐藏**，用户可切换显示（"Thinking"）
- 左边框 + 灰色文字 + `_Thinking:_ ` 前缀
- 过滤 `[REDACTED]` 块

### ToolPart（核心——最复杂的渲染）

#### 通用行为
- **自动隐藏**：如果用户未开启"详情"模式且工具执行成功，整个工具调用**不显示**
- 权限等待中：工具行高亮为 warning 色
- 错误：error 色显示

#### 两种渲染模式

**InlineTool**（单行）：
- 格式：`{icon} {工具摘要}`
- 运行中：用 Spinner 替代 icon
- 被拒绝的错误：删除线文字
- 适用于：简单工具调用或成功完成的工具

**BlockTool**（多行块）：
- 有边框的盒子，背景色区分
- 标题行 + 内容区
- 适用于：有输出/诊断/diff 的工具

#### 各工具的具体渲染

| 工具 | 图标 | 有输出时 | 无输出时 | 显示内容 |
|------|------|----------|----------|----------|
| bash | `$` | BlockTool | InlineTool | 命令、输出（最多10行可展开）、工作目录 |
| glob | `✱` | - | InlineTool | pattern、path、匹配数 |
| read | `→` | - | InlineTool | 文件路径；子项"↳ Loaded"列表 |
| grep | `✱` | - | InlineTool | pattern、path、匹配数 |
| webfetch | `%` | - | InlineTool | URL |
| codesearch | `◇` | - | InlineTool | 查询、结果数 |
| websearch | `◈` | - | InlineTool | 查询、结果数 |
| write | `←` | BlockTool（有诊断） | InlineTool | 文件名、代码+行号、诊断 |
| edit | `←` | BlockTool（有diff） | InlineTool | Split/unified diff 视图、诊断 |
| task | `│` | - | InlineTool | 子agent类型+描述；运行中显示当前工具；完成显示摘要；可点击导航到子会话 |
| apply_patch | `%` | BlockTool（有文件） | InlineTool | 每文件 diff 块 |
| todowrite | `⚙` | BlockTool（有todos） | InlineTool | TodoItem 列表，状态指示器（✓/•/空） |
| question | `→` | BlockTool（有答案） | InlineTool | 问答对 |
| skill | `→` | - | InlineTool | 技能名 |
| 其他 | `⚙` | BlockTool（有输出且开启显示） | InlineTool | 工具名+输入参数；输出（最多3行可展开） |

### 不渲染的 Part 类型（在 assistant message 中）

| Part 类型 | 说明 | 服务端用途 |
|-----------|------|-----------|
| step-start | 不渲染 | 判断中断后是否隐藏消息 |
| step-finish | 不渲染 | - |
| snapshot | 不渲染 | 快照/回滚追踪 |
| patch | 不渲染 | 补丁追踪（视觉 diff 由 Edit/ApplyPatch 工具组件处理） |
| subtask | 不渲染 | 子任务概念通过 task 工具组件表达 |
| retry | 不渲染 | 在输入框状态栏显示重试倒计时 |
| agent | 不渲染 | 在输入框中显示为虚拟文本 |
| compaction | 仅在 UserMessage 中渲染 | 压缩分割线 |

---

## Assistant Message 底部信息

当消息是最后一条或已完成时显示：
- `▣` 图标（agent 颜色）
- 模式名称（首字母大写）
- 模型名称
- 持续时间（如已完成）
- "· interrupted"（如被中断）

---

## 错误状态

- Message error（非中断）：红色边框盒子显示 error.data.message
- MessageAbortedError：底部显示 "· interrupted"
- Tool error：`theme.error` 色文字
- 被拒绝（权限/问题）：删除线文字

---

## 对 Android 端的移植建议

1. **工具调用应区分 Inline/Block 两种模式**：简单的只显示一行摘要，有 diff/输出/诊断的展开显示
2. **成功的工具调用可自动折叠**：默认只显示摘要行，用户点击展开
3. **Reasoning 默认隐藏**：提供开关
4. **step-start/step-finish/snapshot/patch/subtask/retry 不需要渲染**
5. **Compaction 在 user message 中显示为分割线**
6. **Bash 工具**：显示命令，输出最多10行可展开
7. **Edit/Write 工具**：显示 diff 视图
8. **TodoWrite 工具**：显示 todo 列表（已有 TodoListCard）
9. **File parts**：badge + 文件名
