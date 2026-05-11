# Session Compact Async Design

**Date:** 2026-05-11

## Goal

让 Android 的 `compact` 行为与 Web 对齐：调用 `summarize` 时采用 fire-and-forget 语义，不再同步等待结果，不再依赖固定延迟；compact 按钮状态、文案与完成态展示统一由现有同步链路驱动。同时，将 compact 展开内容从普通文本切换为 Markdown 渲染。

## Current State

### Android

- `SessionDetailViewModel.compact()` 调用 `OpencodeApiClient.summarizeSession()`，请求 `POST /session/{sessionID}/summarize`
- 请求发出后，本地执行 `delay(2000)`
- 延迟后主动调用 `incrementalSync(sessionID)`、刷新 session status 与 retry status
- UI 额外维护本地 `_isCompacting` 状态，用于控制 compact 按钮文案和可点击态
- compact summary 展开后使用 `Text` 渲染，未复用项目中已有的 Markdown 组件

### Web

- Web 端 `Compact session` 也是调用 `sdk.client.session.summarize(...)`
- 语义是 fire-and-forget：发起请求后立即返回，不等待 compact 完成
- 后续 UI 更新依赖 Web 自己的同步流与 session status 数据
- 按钮是否可用由同步得到的 session status 决定，而不是本地请求态

## Problem

当前 Android 与 Web 的主要差异不在接口，而在调用模型：

- Android 把 `summarize` 当成同步操作使用，导致超时语义错误
- Android 使用固定 `delay(2000)` 猜测服务端何时完成，不可靠
- Android 维护本地 `_isCompacting`，形成第二套状态源，和同步结果可能短暂冲突
- compact summary 是 Markdown 内容，但当前按纯文本渲染

这会带来以下问题：

- compact 耗时超过客户端超时时，Android 容易过早显示失败，即使服务端仍在继续执行
- 本地请求态与服务端状态不同步时，按钮状态和真实状态可能不一致
- compact 内容的可读性差，列表、代码块、标题等 Markdown 结构丢失

## Approaches Considered

### Option A: Keep `/summarize`, switch Android to fire-and-forget, drive UI from sync state

这是推荐方案。

- 保留当前服务端接口 `POST /session/{sessionID}/summarize`
- Android 发起请求后立即返回，不等待 compact 完成
- 去掉本地 `_isCompacting`
- compact 按钮文案、禁用态统一由同步到的会话状态决定
- compact 完成/失败/中断统一依赖现有 `SSE + 轮询 + 增量同步补偿`
- compact summary 改用现有 Markdown 组件渲染

优点：

- 和 Web 语义一致
- 改动小，不引入新协议
- 消除本地请求态和服务端状态源分裂
- 与现有同步架构一致，不额外发明补偿机制

缺点：

- 请求发出后的即时反馈将更多依赖同步状态回流，而不是本地立即置位

### Option B: Keep request async, but retain local `_isCompacting` as a fallback UI flag

- 发起 `summarize` 后立即返回
- 但本地仍置 `_isCompacting=true`，等同步状态回来后再清理

优点：

- 用户点击后会立刻看到“compacting”反馈

缺点：

- 仍然存在两套状态源
- 更难解释和测试
- 与 Web 仍不完全一致

### Option C: Replace summarize with another async transport

- 例如把 compact 包装成 `prompt_async` 或其他专用请求

优点：

- 表面上更“异步”

缺点：

- 与 Web 实际实现不一致
- 引入新的协议分叉
- 没有必要

## Recommended Design

采用 Option A。

### 1. Network Behavior

- Android 继续使用 `POST /session/{sessionID}/summarize`
- 这个请求只表示“开始 compact”，不表示“compact 已完成”
- Android 调用方不再等待 compact 结束，也不再在 ViewModel 中做固定延迟

### 2. State Ownership

- 删除 `SessionDetailViewModel` 中本地 `_isCompacting`
- compact 相关按钮状态不再由请求发起时的局部布尔值决定
- Android 改为仅依赖同步得到的会话状态来判断：
  - `idle`：显示 `compact`，允许点击
  - `compacting`：显示 `compacting`，禁用点击
  - 其他非 `idle` 状态：禁用点击

这里的核心原则是：

- 请求负责启动动作
- 同步负责反映真实状态
- UI 只认同步态，不认本地猜测态

### 3. Sync Model

compact 完成链路仍依赖现有同步机制，不新增独立“compact 查询”接口。

- 有 SSE 时：优先通过事件快速更新
- SSE 不稳定、后台切换或断连时：仍由轮询和增量同步补偿保证最终一致性
- 不允许把 compact 状态展示只建立在 SSE 上

这与工作区既有约束一致：移动端不能假设 SSE 始终稳定，必须靠同步补偿保证信息完整性。

### 4. Error Handling

Android 仅在“请求根本没有发成功”时提示失败，例如：

- 网络不可达
- 服务端立即返回 4xx/5xx
- 序列化或客户端请求构建失败

Android 不再因为“本地等待超时但服务端可能仍在继续 compact”而直接判定 compact 失败。

这会把失败语义从“完成失败”收窄为“启动失败”，更符合 fire-and-forget 模型。

### 5. Compact Summary Rendering

- `CompactionMessageItem` 展开内容从 `Text` 切换为项目已有的 `MarkdownText`
- 复用当前应用已采用的 Markdown 样式与代码块高亮能力
- 保持当前已修复的约束模型，不重新引入嵌套滚动

这部分是展示层改动，不改变 compact 数据结构与同步策略。

## Files Expected To Change

### Android logic

- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
  - 去掉 `_isCompacting`
  - `compact()` 改为 fire-and-forget
  - 删除 `delay(2000)` 和紧随其后的手动同步假设

- `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
  - compact 按钮的文案和 enabled 逻辑改为由同步状态驱动

### UI rendering

- `android/feature/session/src/main/java/com/openmate/feature/session/component/SessionMessageRenderer.kt`
  - compact summary 展开内容改用 Markdown 渲染

### Tests

- `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
  - 更新 compact 相关测试，验证不再依赖本地 `_isCompacting`
  - 验证 compact 请求发出后不再执行固定延迟和手动完成等待

- `android/feature/session/src/androidTest/java/com/openmate/feature/session/component/SessionMessageRendererCompactionTest.kt`
  - 补 compact summary Markdown 展示用例
  - 保留已修复的 LazyColumn 展开场景回归测试

## Non-Goals

- 不修改服务端 compact 协议
- 不新增专用 compact 查询接口
- 不改变既有同步架构
- 不处理旧 compact 脏数据兼容

## Testing Strategy

### Logic tests

- compact 请求能正常发起
- compact 请求发起后不再依赖本地 `_isCompacting`
- compact 请求发起后不再等待固定 2 秒再同步
- 启动失败时仍能正确提示错误

### UI tests

- `idle` 状态下 compact 按钮可点击，文案为 `compact`
- `compacting` 状态下 compact 按钮禁用，文案为 `compacting`
- 非 `idle` 且非 `compacting` 的忙碌状态下按钮禁用
- compact summary 能按 Markdown 渲染并在列表中安全展开

### Manual verification

- 点击 compact 后，界面不会立即因为本地超时显示失败
- compact 进行中时，按钮状态与服务端同步状态一致
- compact 完成后，新 compact 消息能正常显示并展开 Markdown 内容
- 在 SSE 可用和短暂断连两种场景下，最终状态都能通过同步补齐

## Risks

### Session status propagation delay

如果同步状态回流比本地请求反馈慢，按钮文案切换可能略晚于点击时刻。

接受理由：

- 这是与 Web 一致的语义
- 比维护两套状态更可靠

### Existing status model may not expose `compacting` at every UI point

如果 Android 某些页面当前没有完整消费会话状态，可能需要补齐读取链路。

这属于实现阶段需要核对的边界，不改变本次设计方向。

## Acceptance Criteria

- Android compact 请求语义与 Web 一致，采用 fire-and-forget
- Android 不再使用本地 `_isCompacting` 作为 compact 状态源
- compact 按钮文案和 enabled 逻辑完全由同步状态决定
- Android 不再依赖固定 `delay(2000)` 猜测 compact 完成时间
- compact summary 展开内容按 Markdown 渲染
- 新产生的 compact 可正常展开且不崩溃
