# WorkHard Plugin — 设计文档

## 目标

opencode 在运行中常因以下原因中断：

1. LLM 提供商临时错误（如 Xunfei EngineInternalError），opencode 判定为不可重试，会话停止
2. 模型使用 `question` 工具要求人工回复
3. 权限申请弹窗等待审批
4. （可选）长时间自主运行时 idle 后继续工作

WorkHard 插件监听这些事件，按预设策略自动处理，让会话持续工作。

## 事件处理策略

### 1. Step Failed 自动重试（默认启用）

事件: `session.next.step.failed`

opencode 内部重试机制仅覆盖部分错误（5xx、rate limit 等），Xunfei 的 `EngineInternalError`、`ServiceIsBusyError`、`NotEnoughCvError` 等错误被判定为不可重试，导致会话直接停止。

WorkHard 监听 `step.failed` 事件，检查错误消息是否匹配预设的 retry patterns：
- 匹配 → 等待 `retryDelayMs` → 发送"继续执行上次的任务，刚才的请求失败是临时错误，请重试"
- 不匹配 → 不干预

默认匹配模式: `["EngineInternalError", "ServiceIsBusyError", "NotEnoughCvError", "Xunfei request failed"]`

### 2. Question 自动回复

事件: `question.asked`

- 默认回复: `"按你推荐的进行"`
- 如果 question 有预设选项，选择第一个选项的 label
- 如果 question 支持自定义输入且无预设选项，直接回复自定义文本

### 3. Permission 权限处理

事件: `permission.asked`

| 策略 | 行为 |
|------|------|
| `allow-all` | 所有权限请求自动允许，reply = `"always"` |
| `allow-safe` | 仅允许安全工具（非 edit/write/bash），其他拒绝 |
| `deny-all` | 拒绝所有权限（配合 denyPrompt 使用） |

拒绝时附带 feedback: `"我不能授予你相关权限，请使用其他方法完成任务"`

### 4. 持续工作模式（默认关闭）

事件: `session.idle` / `session.status(type=idle)`

仅当 `autoContinue: true` 时激活：

1. 获取会话最新 assistant 消息
2. 检查是否包含 `endKeyword`
   - 命中 → 停止干预
   - 未命中 → 延迟 `idleDelayMs` → 发送 `continuePrompt`

## 配置

在 `.opencode/config.json` 中声明：

### 仅启用错误自动重试（推荐默认配置）

```jsonc
{
  "plugin": [
    ["file:///D:/openmate/workhard/index.ts", {
      // 自动重试 step.failed 错误
      "retryOnStepFailed": true,
      "retryPatterns": ["EngineInternalError", "ServiceIsBusyError", "NotEnoughCvError", "Xunfei request failed"],
      "retryDelayMs": 3000
    }]
  ]
}
```

### 完整自主工作模式

```jsonc
{
  "plugin": [
    ["file:///D:/openmate/workhard/index.ts", {
      "autoContinue": true,
      "endKeyword": "我已完成10056",
      "continuePrompt": "请检查目标任务文档 xx 中的所有要求是否全部完成，若没有完成继续，若完成回复 我已完成10056",
      "questionReply": "按你推荐的进行",
      "permissionStrategy": "allow-all",
      "retryOnStepFailed": true,
      "retryPatterns": ["EngineInternalError", "ServiceIsBusyError", "NotEnoughCvError", "Xunfei request failed"],
      "retryDelayMs": 3000
    }]
  ]
}
```

### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `retryOnStepFailed` | `boolean` | `true` | 检测 step.failed 自动重试 |
| `retryPatterns` | `string[]` | 见上 | 错误消息匹配模式（包含即匹配） |
| `retryDelayMs` | `number` | `3000` | 重试前延迟 |
| `autoContinue` | `boolean` | `false` | idle 后自动继续（持续工作模式） |
| `endKeyword` | `string` | `"DONE"` | 持续工作结束标记 |
| `continuePrompt` | `string\|null` | 自动生成 | idle continue prompt |
| `maxIdleRetries` | `number` | `50` | 最大 idle 重试次数 |
| `idleDelayMs` | `number` | `3000` | idle 延迟 |
| `questionReply` | `string` | `"按你推荐的进行"` | Question 自动回复 |
| `permissionStrategy` | `string` | `"allow-all"` | 权限策略 |
| `denyPrompt` | `string` | `"我不能授予你相关权限..."` | 拒绝后的反馈 |

## 核心流程

```
Plugin loaded
  → 解析 options
  → 返回 { event: handler }

event handler 收到事件:
  ├── type=session.next.step.failed
  │   ├── error.message 匹配 retryPatterns?
  │   │   ├── YES → delay(retryDelayMs) → send "继续执行"
  │   │   └── NO  → 不干预
  ├── type=permission.asked
  │   ├── allow-all → reply(allow)
  │   └── deny → reply(reject + feedback)
  ├── type=question.asked
  │   └── reply(questionReply 或首个选项)
  ├── type=session.idle 或 session.status(idle)
  │   └── autoContinue=false → 不干预
  │   └── autoContinue=true → 检查 endKeyword → 发送 continuePrompt
  └── type=session.error
      └── 记录日志
```

## 实现要点

1. `step.failed` 后 opencode 可能会自动发 synthetic prompt 继续，此时会话不 idle，WorkHard 的 retry 和 opencode 的 synthetic 可能冲突 → 用 `retryInProgress` 标记防重复
2. `step.failed` 触发的 retry 在延迟期间，如果 opencode 已自动继续，`prompt_async` 会因 busy 失败，静默忽略即可
3. `autoContinue` 默认关闭，只处理错误重试，不会在正常 idle 时打扰
4. retryPatterns 使用包含匹配，用户可自由添加新的提供商错误模式
