# Question Tool Answer Rendering Design

## Goal

在 Android 会话页中补齐 `question` 工具体验，使其同时满足两点：

- 运行中的问题卡片支持 `custom=true` 的自由输入答案
- 问题回复完成后，工具消息优先展示结构化的“问题 -> 用户答案”回显，而不是只剩一段普通结果文本

这次改动同时覆盖 `待跟踪问题.md` 中的问题 7 和问题 21。

## Current State

Android 当前 `question` 工具有两个明显缺口：

1. `QuestionCard` 和 `QuestionDialog` 只支持点击预设选项，不支持 `QuestionInfo.custom`
2. `question` 工具完成后没有专门的结果渲染，仍走普通 tool summary/result 文本，用户看不到原问题和自己的最终选择

相比之下，上游 Web/CLI 已经具备以下能力：

- `custom=true` 时，会提供 “Type your own answer” 入口
- `question` 工具完成后，优先根据 metadata 中的 `answers` 结构化展示每个问题及对应答案
- 用户 dismiss question 时，展示弱提示而不是普通错误卡片

## Constraints

- 保持 Android 现有 `replyQuestion(requestID, answers)` 数据通路不变；服务端接口已经接受 `List<List<String>>`
- 不改同步机制，不额外引入 Room 持久化字段
- 优先做最小正确改动，主要收敛在渲染层和输入组件
- 缺少结构化数据时必须安全回退到现有展示，避免老数据或异常数据导致空白

## Proposed Design

### 1. Running Question Input

对 `QuestionCard` 和 `QuestionDialog` 增加自定义答案能力。

当 `questionInfo.custom != false` 时：

- 在选项列表末尾增加一个固定入口，语义对齐 Web 的 “Type your own answer”
- 点击后展开文本输入框
- 单选题：提交后该题答案为仅包含自定义文本的单元素列表
- 多选题：允许预设选项与一条自定义文本同时存在；自定义文本也作为 answers 中的一个元素
- 若输入框为空，则不写入自定义答案

为减少接口改动，UI 内部仍维护 `Map<Int, List<String>>` 作为每题最终答案，提交时继续走现有 `onReply(List<List<String>>)`。

### 2. Completed Question Rendering

为 `question` 工具增加专门的完成态渲染。

数据来源：

- 问题文本来自 tool args 中的 `questions`
- 用户答案优先来自 tool metadata 中的 `answers`

渲染规则：

- 若能同时解析出问题列表和答案列表，则展示 question answer card：每个问题一行或一块，包含题干和答案文本
- 若某题答案为空，显示“未回答”
- 若拿不到 metadata.answers，则回退到现有普通工具结果展示，不阻断兼容

### 3. Dismissed Question Handling

若 `question` 工具处于 `error` 状态，且 error 文案表示用户 dismiss 了问题，则不显示通用错误卡片，而显示弱提示文案，语义对齐 Web。

### 4. File Boundaries

- `android/feature/session/.../component/PartRenderer.kt`
  - 扩展 question args / metadata 解析 helper
  - 扩展 `QuestionCard`
  - 为 question completed/error 增加专门渲染
- `android/feature/session/.../component/SessionMessageRenderer.kt`
  - 与 `PartRenderer` 行为对齐，避免新旧两套渲染分叉
- `android/feature/session/QuestionDialog.kt`
  - 补齐自定义输入能力
- `android/feature/session/src/test/...`
  - 增加 question 解析与 question tool 渲染相关单测

## Testing Strategy

采用 TDD：

1. 先为 question args / metadata 解析补失败单测
2. 先为完成态结构化回显补失败单测
3. 先为 dismissed question 弱提示补失败单测
4. 先为 custom question 输入/提交数据结构补失败单测
5. 最小实现后运行 `:feature:session:testDebugUnitTest --no-daemon`
6. 最后运行 `:app:assembleDebug --no-daemon`

## Risks

- Compose UI 测试成本较高，因此优先把可验证逻辑抽成纯 helper 或使用现有可测试渲染入口覆盖
- 运行中 question 的自定义输入涉及单选/多选两套行为，若状态管理写散，后续容易再出回归；需要把自定义值与最终答案列表的映射收敛在同一处
- `question` 完成态可能遇到旧消息不含 metadata.answers，因此必须保留回退逻辑

## Acceptance Criteria

- `custom=true` 的 question 在 Android 上可输入自定义答案并正常提交
- question 回复完成后，Android 会优先展示“问题 -> 用户答案”的结构化回显
- dismiss question 不再显示普通错误卡片，而是显示弱提示
- `:feature:session:testDebugUnitTest --no-daemon` 通过
- `:app:assembleDebug --no-daemon` 通过
