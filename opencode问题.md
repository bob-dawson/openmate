# OpenCode 上游问题记录

## 1. TUI 切换会话后不显示新消息

**现象**: 手机通过 API 发送消息后，电脑端 opencode TUI 不显示。即使切换到其他会话再切回来也不显示。

**根因**: `packages/opencode/src/cli/cmd/tui/context/sync.tsx:499-520`

```ts
async sync(sessionID: string) {
  if (fullSyncedSessions.has(sessionID)) return  // 已同步过直接跳过
  // ... 从API拉取消息 ...
  fullSyncedSessions.add(sessionID)
}
```

`fullSyncedSessions` 是一个 `Set<string>`，一旦 session 被完整同步过，后续切换回来时 `sync()` 直接 return，不会重新从 API 拉取消息。TUI 完全依赖实时 SSE `message.updated` 事件来更新消息列表，但如果切走期间收到了新消息，`store.message[sessionID]` 可能未被正确维护。

**修复建议**:
- 切回已同步的 session 时，仍应调用 API 检查是否有新消息（增量拉取）
- 或在 `message.updated` SSE 事件处理中，对 `store.message[sessionID]` 不存在的情况做更鲁棒的处理

**状态**: 待 OpenMate 发布后向上游提 issue
