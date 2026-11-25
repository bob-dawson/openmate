# OpenMate 消息支持更新（对齐 opencode 2.4.1）

> 状态：已实施，Docker + 模拟器验证通过（未发版）
> 起因：opencode 升级到 2.4.1 后数据库与消息模型变化，手机端出现「会话只显示两条消息」「搜索面板大量空白助手消息」等问题。
> 已发布修复：0.3.29（bridge 合并 `session_part` + 手机端历史空消息自愈）。

## 1. 背景

opencode 2.4.1 相对旧版本（2.0.x）的数据库变化：

| 变化 | 影响 | 状态 |
|---|---|---|
| 新增 `session_part` 表，assistant 的 content 从 `session_message.data.content` 拆出 | 消息内容为空、搜索预览为空 | 0.3.29 已修复 |
| 新增 `blob` / `blob_ref` 表（`experimental.media_blob` 开关） | 用户图片/文件字节可能改为 `media:<sha256>` 引用 | **待修复**（bridge 只还原图片） |
| 新增 `session_inbox`（发送队列），`session.prompt` 改为入队即返回 | 手机端「是否在队列中/是否已处理」判断与 CLI 不一致 | **待修复** |
| `event` 表不再写入（0 行），`event_sequence` 仍维护 | 同步游标正常（bridge 已走 `event_sequence`），无影响 | 无需处理 |
| 新增 `job` / `automation` / `schedule` / `machine` / `credential` | 与消息无关 | 无需处理 |
| `session_v2` 新增 `time_busy` | bridge 只查固定列 | 无需处理 |
| 新增 `session.status` 不再发出，改为 `session.execution.*` | 手机端忙碌状态原本靠消息推导，暂不改 | 观察项 |

## 2. opencode 2.4.1 消息模型

### 2.1 消息类型（`Session.Message.Type`）

| 类型 | 关键字段 | 说明 |
|---|---|---|
| `user` | `text`, `files`, `agents`, `skills`, `metadata`, `time` | 用户消息；files 可能是 inline base64 或 `media:` 引用 |
| `assistant` | `agent`, `model`, `content[]`, `snapshot`, `finish`, `cost`, `tokens`, `error`, `retry`, `time` | 一个模型步骤；`content` 来自 `session_part` |
| `compaction` | `status: running/completed/failed`, `reason`, `summary`, `error`（failed 时）, `model`, `cost`, `tokens` | 上下文压缩 |
| `idle` | `outcome: succeeded/failed/interrupted`, `error` | 回合结束标记，无渲染高度 |
| `synthetic` | `text`, `description`, `metadata` | 注入给模型的上下文（如后台任务提示） |
| `system` | `text`（模型可见）, `description`（人类可读，可选） | 系统/指令更新 |
| `shell` | `shellID`, `command`, `status`, `exit`, `output`, `time` | 后台 shell 生命周期 |
| `skill` | `skill`, `name`, `text` | 技能调用 |
| `model-switched` | `model`, `previous` | 模型切换 |
| `agent-switched` | `agent`, `previous` | agent 切换 |
| `location-switched` | `location`, `projectID`, `subpath`, `previous` | 会话被移动到其他目录（`session.moved`） |

### 2.2 assistant content part

`text` | `reasoning` | `tool`

tool part：

```jsonc
{
  "type": "tool",
  "id": "call_xxx",          // 也是 callID
  "name": "shell",
  "executed": false,
  "state": {
    "status": "streaming|running|completed|error",
    "input": { ... },
    "content": [
      { "type": "text", "text": "..." },
      { "type": "file", "uri": "media:<sha256>|data:...", "mime": "image/png", "name": "..." }
    ],
    "metadata": { ... },
    "error": { ... }
  },
  "time": { "created": ..., "completed": ... }
}
```

### 2.3 发送队列（session_inbox）

- `session.prompt` 现在是**入队即返回**：返回 `SessionInbox.User`，其 `id` 与投递后 `session_message.id` **完全相同**
  （opencode `projector.ts` 的 `InboxDelivered` 分支直接用 `inboxID` 插入消息，已从源码确认）
- `delivery`：
  - `steer`（**默认**）：在当前一步的模型调用前插入，可以在忙时插话
  - `queue`：等当前工作结束后，逐个投递
- `resume: false`：只入队，不启动 agent（App 不使用）
- 队列事件：`session.inbox.enqueued` / `delivered` / `cancelled` / `delivery.changed`
- `GET /api/session/:id/inbox` 列出未投递项（按 `enqueued_seq` 升序）
- `DELETE /api/session/:id/inbox/:inboxID` 取消
- `PATCH /api/session/:id/inbox/:inboxID` body `{"delivery":"steer|queue"}` 改投递方式
- 队列项类型：`user` / `synthetic` / `compaction` / `move`

### 2.4 用户消息的两个时间（关键）

| 时间 | 来源 | 含义 |
|---|---|---|
| 入队时间 | `session_inbox.time_created`，prompt 响应里的 `time.created` | 用户点发送的时间 |
| 投递/处理时间 | `session_message.time_created`（= `InboxDelivered` 事件时间） | agent 真正接收该消息的时间 |

**结论**：agent 忙时发送的消息，入队时间早、投递时间晚。时间线必须按投递时间（即 `session_message.time_created`）排序，
这与实际处理顺序一致。当前 App 的「乐观消息」用入队时间插入时间线，才是顺序错乱的根因。

### 2.5 回退（revert）前提

`POST /api/session/:id/revert/stage` 只有两个失败原因：

1. `SessionBusyError`：会话仍在运行（`execution.isActive` 为真）
2. `MessageNotFoundError`：作为边界的消息不在 `session_message` 里（即消息还没投递，仍在 `session_inbox`）

因此「消息一发送就允许回退」必然出现失败。

### 2.6 会话移动

`POST /api/session/:id/move`，body `{ "directory": "/abs/path", "delivery": "steer|queue" }`。
移动后发 `session.moved` 事件，写入一条 `location-switched` 消息。桌面/Web 会话菜单、CLI/SDK、
`opencode.session.move` 工具都可触发；Android 端暂不支持移动。

## 3. Android 现状与结论

| 类型 | 现状 | 结论 |
|---|---|---|
| `user` | `UserMessageItem` + 图片 `[图片N]` | ✅ 保持；但改为只从时间线展示已投递消息 |
| `assistant` | `AssistantMessageItem`（text/tool/reasoning）+ 错误卡片 | ✅ 保持 |
| `compaction` | `CompactionMessageItem`，可展开 summary | ⚠️ 补 failed 时的 error 展示 |
| `idle` | 不渲染（0 高度），用于回合/忙碌判断 | ✅ 保持 |
| `synthetic` | `SyntheticMessageItem` | ✅ 保持 |
| `shell` | `ShellLifecycleMessageItem` | ✅ 保持 |
| `skill` | `SkillMessageItem` | ✅ 保持 |
| `model-switched` | 不渲染；`buildUserModelMap` 用于给用户消息标注模型 | ✅ 保持 |
| `agent-switched` | 不渲染 | ✅ 保持（界面已有 agent 显示区） |
| `location-switched` | 不渲染 | ✅ 暂不渲染（仅 web/CLI 移动会话时出现） |
| `system` | 不渲染 | **补渲染**（显示 `description`，可展开 `text`） |

其他差异：

| 项 | 现状 | 结论 |
|---|---|---|
| 用户消息「是否排队/是否处理」 | 乐观消息 + `roundMark` 推断 | **重做**：读 `session_inbox`，pending 单独展示 |
| 用户消息排序 | 乐观消息按入队时间插入时间线 | **重做**：时间线只放已投递消息，pending 放输入框上方 |
| 回退入口 | 任何用户消息都能点，忙时先 abort 再 sleep 500ms | **调整**：pending 无回退入口；已投递消息保持「停止并回退」，改为等服务端真正空闲再 stage |
| 发送方式 | 固定默认 `steer`，无法选择 | **新增**：输入栏可选「插话 / 排队」 |
| 工具结果里的 `file` 项 | `extractToolItems` 对非 text 项 `toString()` → 输出 base64/JSON | **必须修复**：渲染为 `[图片]` / `[文件]` |
| 图片 blob 引用 | bridge 把 `media:<sha256>` 原样交给手机，App 读不到 `data` | **必须修复**：bridge 还原图片文件 |
| 工具结果内联 base64 | 会被完整写入 App 数据库 | **顺手修复**：落库前剥掉 `data:` URI |
| 搜索面板预览 | compaction / shell / system 显示「—」 | 顺手补摘要/命令 |
| bridge 事件过滤 | `session.inbox.*` 既不下发也不触发同步 | **必须修复**：加入白名单 |

## 4. 实施项

### 4.1 bridge

1. ✅ `session_part` 合并（0.3.29）
2. **parts 合并顺序**对齐官方：`content = [...parts, ...inline]`（当前为「inline 为空才用 parts」）
3. **用户图片 blob 还原**
   - 只处理 `data.files[]`：`uri` 命中 `^media:[0-9a-f]{64}$` 且 `mime` 为 `image/*` → 从 `blob.data` 取字节转 base64 写入 `data`，删除 `uri`
   - blob 缺失 → 按 opencode 行为降级成 `[media unavailable: name (mime)]`（`mime` 改 `text/plain`、`data` 为 base64 文本）
   - 非图片文件保持 `media:` 引用不展开（App 只显示文件名，避免把大文件塞进同步）
   - 覆盖 `/init`、增量 sync、`/full` 三条读取路径
4. **工具 file 项瘦身**：`content[].state.content[]` 中 `type=file` 且 `uri` 以 `data:` 开头时去掉 `uri`（保留 `mime`/`name`），避免把 base64 传上手机
5. **事件过滤**（`src/events/filter.rs`）
   - `retain_session_event` 增加 `session.inbox.enqueued|delivered|cancelled|delivery.changed`
   - `build_compat_sync_notification` 增加同上（投递时也能立即触发消息增量同步）
6. **不新增 inbox 接口**：App 直接走 opencode REST `/api/session/:id/inbox`（bridge 已有 `/api/session/*` 透传）

### 4.2 Android

1. **工具 file 项占位**（P0）
   - `extractToolItems`：`resultText` 只拼接 `type=text` 项；`type=file` 项转成 `[图片] name` / `[文件] name` 文本行
   - 不再对任何非 text 项调用 `toString()`
2. **队列状态 + 排序**（P0）
   - 新增 `PendingPrompt` 模型：`id / text / delivery / createdAt / imageCount / fileCount / localOnly`
   - `OpencodeApiClient` 新增：
     - `listSessionInbox(sessionID, directory)`（带 `x-opencode-capabilities: experimental-media-uri`，避免拉回 base64 图片）
     - `cancelSessionInboxItem(...)`
     - `updateSessionInboxDelivery(...)`
   - `SessionDetailViewModel`：
     - `pendingPrompts: StateFlow<List<PendingPrompt>>`
     - 打开会话、发送成功、收到 `session.inbox.*`、15s 轮询时刷新 inbox
     - pending 列表 = 服务端 inbox（顺序按 `enqueued_seq`）∪ 本地刚发送但还没出现在 DB 的项（TTL 兜底，兼容旧版 opencode）
     - 消息流里出现同 id → 立即从 pending 移除
     - 取消 pending、切换单条 delivery
   - **删除**「乐观消息写库」`insertOptimisticUserMessage` 调用及相关 `pendingLocalMessages` 保护逻辑
   - **删除** `queuedMessageIds` / `buildQueuedMessageIds` / 时间线重排（`SessionDetailScreen` 的 `rest + queued`）
   - UI：输入框上方显示 pending 条（状态徽标「排队中 / 待插入 / 投递中」+ 文本预览 + `[图片N]` + 操作入口）
   - pending 操作：
     - 切换投递方式（`PATCH`，steer ↔ queue）
     - 删除（`DELETE`）
     - 编辑（仅无图片/文件的 user 项）：弹窗改文本 → 先 `DELETE` 旧项 → 用
       `GET /api/session/:id/message/:msgID` 校验旧项是否已被投递；未被投递则用原 delivery 重新发送，
       已被投递则提示「消息已发送，无法修改」且不再重发
3. **发送方式选择**（P0）
   - 空闲：保持现状，直接以 `steer` 发送
   - 忙碌：点击发送后弹出选择「插话（下一步调用前插入）/ 排队（当前工作结束后发送）」，取消则不发送
   - `sendPrompt(..., delivery)` 传入 prompt body
4. **回退校验**（P0）
   - 时间线只含已投递消息 → pending 消息天然没有回退入口（pending 只提供改投递方式/删除/编辑）
   - 回退已投递消息时保持当前交互：忙则直接停止当前生成，再回退
   - 固定 500ms 等待改为「abort → `POST /api/experimental/session/:id/wait` 等服务端真正空闲 → stage」，
     仍失败则重试一次并给出明确错误提示
   - 回退成功后取消该会话所有 pending 项（它们的 `enqueued_seq` 必然在边界之后，opencode 提交回退时也会删除它们）
   - `revertToLastMessage` 走同一逻辑
5. **`system` 渲染**（P1）：一行灰色提示，显示 `description`（为空用 `text`），点击展开全文
6. **`compaction` failed**（P1）：标题显示失败，正文展示 `error` 摘要
7. **搜索面板预览**（P2）：`user`/`assistant`/`compaction`(summary)/`shell`(command)/`system`(description)/`synthetic`(text)/`skill`(name)
8. **落库瘦身**（P2）：`MobileTruncator` 对 assistant tool 的 file 项剥掉 `data:` URI（防旧版 bridge / 其他来源）

### 4.3 不做

- `agent-switched`、`location-switched`、`idle`：保持不渲染（逻辑用途除外）
- 工具 file 项点击查看大图/打开 blob：本轮只做占位；`read` 工具本身有可点击路径，走现有文件预览
- `session.execution.*` 驱动的忙碌状态重构：本轮不改（回退用 abort + wait 兜底）

### 4.4 实施中额外发现并修复

1. **同步前截断把用户图片字节也删了**：`sync/truncate.rs` 的 `truncate_user` 对所有 files 调 `sanitize_file_item`，
   把 `data`（base64）丢掉，导致 bridge 即使还原了 blob，手机端仍收不到图片。改为 `sanitize_user_file_item`：
   图片保留 `data`，非图片仍只保留元数据。
2. **回退后发送新消息被本地回退过滤隐藏**：`sendMessageWithDelivery` 里清除本地 revert 的代码放在
   `incrementalSync` 之后，同步异常时会跳过。改为成功发送后立即清除，再同步。
3. **问题卡片不显示选项描述**：`QuestionDialog` 和内联 `QuestionCard` 只渲染 `option.label`，
   opencode 提供的 `option.description` 被丢弃。已补在选项下方以小字显示。
4. **pending 编辑后残留旧项**：取消旧 inbox 项后未清理 `localPendingPrompts`，刷新会把它当成本地待投递项
   再显示一条。编辑成功时同步清理。
5. **Docker 构建镜像源**：`npm install -g @opencode/cli@latest` 默认源会超时，Dockerfile 增加
   `NPM_REGISTRY=https://registry.npmmirror.com` 构建参数；测试配置打开 `experimental.media_blob`。

## 5. 队列语义（已从源码确认）

- prompt 返回的 `id` 就是投递后的 `session_message.id`
- `steer` 在下一步模型调用前插入；`queue` 等当前工作全部结束后投递
- 同回合多条 `steer` 按 `enqueued_seq` 全部插入；`queue` 每次只投递一条
- 消息的 `session_message.time_created` 是投递时间，因此时间线按时间排序 = 处理顺序
- `queued_seq` 与 `session_message.seq` 来自同一事件序列，可直接比较（回退清理依赖）

## 6. 测试计划

Docker 容器当前 bridge 0.3.6 / opencode 2.0.15，复现不了新迁移。测试环境需要重建：

- **A（先用）**：模拟器直连本机 bridge（真实 opencode 2.4.1 + 真实数据库）
- **B（回归）**：启动 Docker → 重新构建镜像（`@opencode/cli@latest` 即 2.4.1 + 新 bridge 二进制）→ 在测试配置中打开
  `"experimental": { "media_blob": true }` → 端到端

验证清单：

1. 长会话打开后消息完整渲染，助手消息预览正常
2. 空闲时发送 → 消息很快进入时间线，时间正确
3. agent 忙时发送两条（一条 `steer`、一条 `queue`）→ 输入框上方显示 pending；投递后按顺序进入时间线，pending 消失
4. pending 消息没有回退入口，只能改投递方式/删除/编辑
5. 忙时对已投递消息点「回退到此处」→ 自动停止并成功回退；回退成功后 pending 被清空
6. 编辑 pending 文本 → 重发成功；编辑期间被投递 → 提示「已发送，无法修改」且不产生重复
7. 发送图片（开启 `media_blob`）→ `blob` 表有记录 → 时间线图片正常显示、可点开
8. 工具返回图片/文件 → 界面只显示 `[图片]/[文件]` 占位，无 base64；`read` 工具路径仍可点击预览
9. `system` 消息显示一行提示，可展开
10. compaction 失败 → 显示失败原因
11. 搜索面板各类型预览正常

## 7. 影响文件清单

**bridge**

- `opencode-bridge/src/sync/db.rs`：parts 合并顺序、用户图片 blob 还原、工具 file 项瘦身、对应单测
- `opencode-bridge/src/sync/truncate.rs`：用户图片保留 `data`、inbox 入队事件剥离 payload
- `opencode-bridge/src/events/filter.rs`：`session.inbox.*` 下发与同步通知、单测

**android**

- `core/network/OpencodeApiClient.kt`：inbox list/cancel/update、`sendPrompt(delivery)`、`waitSession`、`messageExists`
- `core/network/dto/SessionInboxDto.kt`（新增）
- `core/data/repository/SessionMessageRepositoryImpl.kt`：移除乐观消息与 `pendingLocalMessages`
- `core/data/sync/MobileTruncator.kt`：工具 file 项去 base64
- `core/domain/repository/SessionMessageRepository.kt` + 测试假实现
- `core/data/sse/EventDispatcher.kt`：`session.inbox.*` 触发同步/刷新
- `feature/session/SessionDetailViewModel.kt`：pending 状态、delivery、回退校验、移除 queued 推断
- `feature/session/SessionDetailScreen.kt`：pending 条、回退禁用、移除时间线重排
- `feature/session/component/PendingPromptsBar.kt`（新增）：输入框上方的排队消息条
- `feature/session/component/QuestionDialog.kt`：选项描述展示
- `feature/session/component/SessionMessageRenderer.kt`：工具 file 占位、`system` 渲染、compaction failed
- `feature/session/component/SessionMessagePartRenderer.kt`：内联问题卡片选项描述
- `feature/session/component/SessionMessageSearchPanel.kt`：预览与回退禁用
- `feature/session/res/values/strings.xml` + `values-zh/strings.xml`：新增文案
- 对应单测（ViewModel / API / truncator / renderer 纯函数）

## 8. 已确认的交互细节

1. pending 条放在**输入框上方**，按 `enqueued_seq` 顺序展示
2. pending 中的图片只显示 `[图片N]`，不下载缩略图
3. 发送方式：**仅忙碌时**在点击发送后弹出选择（插话 / 排队）；空闲直接发送（`steer`）
4. 回退：保持「直接停止并回退」；未被投递的消息不提供回退，只提供切换投递方式 / 删除 / 编辑（文本）
5. 工具返回的图片/文件：本轮只做占位。`read` 工具读图片/PDF 确实会返回 `type:"file"`（默认行为），
   但该工具行已有可点击的文件路径、可走现有文件预览打开，因此不需要再实现 blob 点开查看
6. pending 编辑的并发处理：保存前先取消并校验旧项是否已投递；已投递则**不重发**，提示用户
7. 工具结果中的 base64 同时在 bridge 与 App 落库前剥掉，避免手机数据库膨胀
8. 问题卡片的选项描述（`option.description`）与标题一并显示

## 9. 验证结果（2026-11-25，Docker opencode 2.4.1）

环境：Docker `openmate-bridge` + 新建容器内 opencode 2.4.1（`experimental.media_blob=true`）+
模拟器 `Small_Phone` + 新 APK。

已验证：

1. 空闲发送：消息立即进入时间线，时间为投递时间 ✅
2. 忙碌发送：弹出「插话 / 排队」选择 ✅
3. pending 条显示在输入框上方；点击徽标可在「排队 / 插话」间切换 ✅
4. pending 编辑：改文本后重发成功，不再残留旧项；含附件时不允许编辑 ✅
5. 忙碌时回退：自动停止当前任务 → 等待空闲 → stage 成功；输入框恢复原文 ✅
6. 回退后 pending 被取消，时间线只保留边界前消息 ✅
7. 回退后发送新消息：`Unrevert` 立即消失，新消息正常显示 ✅
8. 图片（`media_blob`）：opencode 写入 `blob`，bridge 还原为 base64 并下发，手机端图片可点开 ✅
9. 问题卡片：选项描述与标题都显示 ✅
10. `system` 消息（修改 AGENTS.md 触发）：显示一行 `Instructions updated: ...` ✅
11. 工具 `read` 图片：显示可点击文件路径，不输出 base64 ✅
12. bridge 单测 254 项、Android `core:data` / `feature:session` 单测全部通过 ✅

未验证（缺少稳定触发手段）：compaction failed 的失败原因展示（代码复用现有错误提取逻辑）。
