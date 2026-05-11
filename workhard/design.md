# WorkHard Plugin — 设计文档

## 目标

opencode 在长时间自主运行中，常因以下原因中断：

1. 模型倾向问无意义问题（如"如果你没有意见，我将继续工作"），等待人工回复
2. 模型使用 `question` 工具要求人工回复
3. 权限申请弹窗等待审批
4. 网络错误导致会话挂起

WorkHard 插件监听这些事件，按预设策略自动回复，让会话持续工作直到出现指定的结束标记。

## 典型使用场景

用户启动一个长任务（如"按待跟踪问题文档逐项处理"），配置结束标记为 `我已完成10056`：
- 遇到 question → 自动回复"按你推荐的进行"
- 遇到权限请求 → 根据预设策略允许或拒绝；拒绝后发送"我不能授予你相关权限，请使用其他方法"
- 遇到 idle → 发送"请检查目标任务文档 xx 中的所有要求是否全部完成，若没有完成继续，若完成回复 我已完成10056"
- 检测到"我已完成10056" → 停止干预

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                    WorkHard Plugin                        │
│                                                          │
│  ┌─────────────┐   ┌───────────────┐   ┌─────────────┐  │
│  │ Event Hook  │   │  Permission   │   │   Session   │  │
│  │ (bus event) │──▶│  Strategy     │   │   Monitor   │  │
│  └──────┬──────┘   └───────────────┘   └──────┬──────┘  │
│         │                                       │        │
│         ▼                                       ▼        │
│  ┌───────────────────────────────────────────────────┐   │
│  │                 Strategy Engine                    │   │
│  │  - question.asked  → auto reply (自定义回复)      │   │
│  │  - permission.asked → allow / deny + deny prompt  │   │
│  │  - session.idle    → check end keyword / prompt   │   │
│  │  - session.error   → log + continue               │   │
│  └───────────────────────────────────────────────────┘   │
│         │                                                 │
│         ▼                                                 │
│  ┌──────────────┐                                        │
│  │  HTTP Client │  (opencode REST API :4096)             │
│  └──────────────┘                                        │
└──────────────────────────────────────────────────────────┘
```

## 事件处理策略

### 1. Question 自动回复

事件: `question.asked`

- 调用 `client.question.reply({ requestID, answers: [[config.questionReply]] })`
- 默认回复: `"按你推荐的进行"`
- 如果 question 有 `custom: true`，直接回复自定义文本
- 如果 question 有预设选项，选择第一个选项的 label

### 2. Permission 权限处理

事件: `permission.asked`

根据配置的策略决定：

| 策略 | 行为 |
|------|------|
| `allow-all` | 所有权限请求自动允许，reply = `"always"` |
| `allow-safe` | 仅允许安全工具（非 edit/write/bash），其他拒绝 |
| `deny-all` | 拒绝所有权限（配合 denyPrompt 使用） |
| 自定义规则 | 按 permission + pattern 匹配规则表 |

拒绝后自动发送 denyPrompt（默认: `"我不能授予你相关权限，请使用其他方法完成任务"`）

### 3. Session Idle 处理

事件: `session.idle` / `session.status(type=idle)`

1. 获取会话最新消息
2. 提取最后一条 assistant 消息的文本内容
3. 检查是否包含 `endKeyword`
   - 命中 → 标记完成，停止干预，输出日志
   - 未命中 → 延迟 `idleDelayMs` → 发送 `continuePrompt`

continuePrompt 默认: `"请检查所有任务是否全部完成，若没有完成继续，若完成回复 {endKeyword}"`

### 4. Session Error 处理

事件: `session.error`

- 记录错误日志
- 不主动干预（opencode 自身有 retry 机制）

## 配置

在 `.opencode/config.json` 中声明：

```jsonc
{
  "plugin": [
    ["file:///D:/openmate/workhard/index.ts", {
      // 结束标记：assistant 回复中包含此字符串时停止
      "endKeyword": "我已完成10056",

      // Question 自动回复文本
      "questionReply": "按你推荐的进行",

      // 权限策略: "allow-all" | "allow-safe" | "deny-all"
      "permissionStrategy": "allow-all",

      // 权限拒绝后的提示
      "denyPrompt": "我不能授予你相关权限，请使用其他方法完成任务",

      // idle 后发送的 continue prompt（{endKeyword} 会被替换）
      // 不配置则自动生成: "请检查所有任务是否全部完成，若没有完成继续，若完成回复 {endKeyword}"
      "continuePrompt": null,

      // 最大 idle 重试次数
      "maxIdleRetries": 50,

      // idle 后延迟多久发送 prompt（ms）
      "idleDelayMs": 3000
    }]
  ]
}
```

### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `endKeyword` | `string` | `"DONE"` | 结束关键词 |
| `questionReply` | `string` | `"按你推荐的进行"` | Question 自动回复 |
| `permissionStrategy` | `string` | `"allow-all"` | 权限策略 |
| `denyPrompt` | `string` | `"我不能授予你相关权限..."` | 拒绝后的提示 |
| `continuePrompt` | `string\|null` | 自动生成 | idle continue prompt |
| `maxIdleRetries` | `number` | `50` | 最大 idle 次数 |
| `idleDelayMs` | `number` | `3000` | idle 延迟 |

## 文件结构

```
D:\openmate\workhard\
├── design.md          # 本设计文档
├── package.json       # 包描述
├── index.ts           # 插件入口（Plugin 函数）
└── src/
    ├── config.ts      # 配置解析与默认值
    ├── monitor.ts     # 事件监控主逻辑
    └── log.ts         # 日志工具
```

## 核心流程

```
Plugin loaded
  → 解析 options
  → 返回 { event: handler }

event handler 收到事件:
  ├── type=permission.asked
  │   ├── strategy=allow-all → client.permission.reply(allow)
  │   └── strategy=deny → client.permission.reply(reject)
  │                        → client.session.prompt(denyPrompt)
  ├── type=question.asked
  │   └── client.question.reply(questionReply)
  ├── type=session.idle 或 session.status(idle)
  │   ├── 获取最新消息文本
  │   ├── 包含 endKeyword?
  │   │   ├── YES → finished=true, 打印日志, 停止
  │   │   └── NO  → idleRetries++ → delay → send continuePrompt
  └── type=session.error
      └── 记录日志
```

## 实现要点

1. **幂等性**: reply 操作 try-catch 包裹，已处理的请求会返回错误，静默忽略
2. **并发控制**: 用 `Set<sessionID>` 追踪正在处理 idle 的会话，避免重复发送
3. **结束检测**: 只检查 idle 时的最新 assistant 消息（不检查 permission/question 事件的辅助消息）
4. **会话过滤**: 监控第一个触发 idle 的会话，或配置中指定的 sessionID
5. **安全上限**: maxIdleRetries 防止无限循环
