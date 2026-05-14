# 步骤六：同步保障验证

## 背景

前五步实现了代码层面的改动，本步骤是手动验证计划，确保 revert/unrevert 的端到端数据流正确。

## 验证场景

### 场景一：Revert 基本流程

1. 打开一个有多轮对话的 session
2. 通过搜索面板长按某条 user 消息 → "回滚至此" → 确认
3. **预期**：
   - 该 user 消息之后的所有消息从 UI 消失
   - TopBar 按钮切换为"恢复"图标
   - 输入框上方显示 revert 指示条
4. 用 `analyze_android_db.py` 检查：
   - SessionEntity 中 `revertMessageID` 有值
   - 消息仍在 DB 中（revert 不立即删除，只是隐藏显示）

注意：当前 Android 渲染逻辑可能没有按 `revert.messageID` 过滤消息。如果 UI 上被回滚的消息仍可见，需要检查是否需要在消息列表过滤中添加 revert 逻辑。Web 端的做法是 `userMessages().filter(m => m.id < revert.messageID)`。

### 场景二：Unrevert 恢复

1. 在 revert 状态下点击"恢复"按钮 → 确认
2. **预期**：
   - 之前隐藏的消息重新出现
   - revert 指示条消失
   - TopBar 按钮切换回回滚图标
3. 用 `analyze_android_db.py` 检查：
   - SessionEntity 中 `revertMessageID` 为 null

### 场景三：Revert 后发新消息（Cleanup）

1. 回滚到某条消息
2. 在输入框发送新 prompt
3. **预期**：
   - 服务端执行 cleanup，删除 revert 点之后的所有消息
   - Android 通过 SSE/增量同步收到 `message.removed` 事件
   - DB 中旧消息被删除（步骤一的 EventReplayer 改动）
   - 新的 user + assistant 消息正常显示
4. 用 `analyze_android_db.py` 对比前后消息数量

### 场景四：冷启动兜底

1. 执行 revert 操作
2. 杀掉 Android 应用
3. 重新打开，进入同一 session
4. **预期**：
   - initSync 全量替换消息，revert 状态正确恢复
   - UI 显示 revert 指示条

### 场景五：SSE 断连兜底

1. 断开网络
2. 在 TUI 端执行 revert
3. 恢复网络
4. Android SSE 重连后增量同步
5. **预期**：revert 状态和消息变化最终一致

## 可能需要额外处理的问题

### ~~消息过滤~~ → 已在步骤五 5.5 实现

MessageID 格式为 `msg_` + 12 hex 时间戳 + 14 random base62，ascending 模式生成，**字符串字典序等价于时间序**。因此 `id < revert.messageID` 的字符串比较可以正确判断消息是否在回滚点之前，无需额外转换。
