# 步骤 6：迁移 — 旧模型→新模型切换

> **For agentic workers:** REQUIRED SUB-KILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现从旧锚点同步到新事件同步的切换，确保用户升级后任何会话都不会出现空消息。

**Key Insight:** `/sync/history` API 对所有会话（包括环境变量设置前创建的旧会话）均返回完整事件历史。这是唯一需要的同步路径。v2 消息 API (`GET /api/session/:sessionID/message`) 对旧会话返回空（SessionMessageTable 由 projectors 实时填充，不回填历史），因此不能作为 fallback。

**Architecture:**
- 删除旧 DB 重建，新 DB 只含新表
- Bridge 通过环境变量启用事件同步支持
- 所有会话（新旧）统一通过 `/sync/history` 拉取事件 → EventReplayer 回放 → 重建 SessionMessage 数据
- SSE `session.next.*` 事件用于实时增量更新
- 无需双写、无需 ConsistencyChecker、无需 LegacyMessageConverter

**Tech Stack:** Kotlin, Hilt, Room

---

## 迁移场景分析

### 场景 1：新安装
用户首次安装 OpenMate app，bridge 已启用事件同步。
- **流程**：纯事件同步（`/sync/history` + SSE `session.next.*`）
- **无需 fallback**

### 场景 2：升级（bridge 已启用事件同步）
用户从旧版升级，bridge 已设置 `OPENCODE_EXPERIMENTAL_WORKSPACES=true`。
- **DB 处理**：DB version 变更 + `fallbackToDestructiveMigration`，旧表自动删除重建
- **历史会话**：`/sync/history` 返回完整事件历史，EventReplayer 回放即可重建所有 SessionMessage
- **流程**：进入会话 → `/sync/history` 拉取事件 → EventReplayer 回放写入 SessionMessageEntity → SSE 持续增量更新
- **无需 v2 消息 API fallback，无需旧 API 转换**

### 场景 3：升级（bridge 未启用事件同步）
用户从旧版升级，bridge **未**设置环境变量。
- **结果**：`/sync/history` API 不可用（服务端未启用）
- **Fallback**：保留旧锚点同步路径（不删除旧代码，仅在新路径失败时回退）
- **这是最坏情况，应通过 bridge 设置环境变量来避免**

---

## Files

- Modify: `core/data/src/main/java/com/openmate/core/data/repository/MessageRepositoryImpl.kt`
- Modify: `core/data/src/main/java/com/openmate/core/data/sync/SyncManager.kt` (或对应的同步入口)

---

## Task 1: Implement sync path selection in MessageRepository

**Files:**
- Modify: `core/data/src/main/java/com/openmate/core/data/repository/MessageRepositoryImpl.kt`

- [ ] **Step 1: Add event sync first, legacy anchor sync as fallback**

当用户打开一个会话时，优先尝试事件同步路径。仅在事件同步不可用时（bridge 未启用环境变量），回退到旧锚点同步。

```kotlin
override suspend fun syncMessages(sessionID: String, initialLimit: Int): String? {
    val db = dbProvider.getActive()
    val newCount = db.sessionMessageDao().countBySession(sessionID)

    if (newCount > 0) {
        Log.d(TAG, "Session $sessionID has $newCount messages via event sync, skip")
        return null
    }

    // 1. 优先：事件同步路径
    runCatching {
        eventSyncEngine.syncSession(sessionID)
    }.onSuccess {
        val count = db.sessionMessageDao().countBySession(sessionID)
        if (count > 0) {
            Log.i(TAG, "Event sync loaded $count messages for $sessionID")
            return null
        }
    }.onFailure {
        Log.w(TAG, "Event sync failed for $sessionID, falling back to anchor sync", it)
    }

    // 2. Fallback：旧锚点同步（仅当事件同步不可用时）
    // ... existing anchor sync logic ...
}
```

核心逻辑：
1. 检查本地是否已有 SessionMessage → 有则跳过
2. 尝试事件同步（`/sync/history` 拉取 → EventReplayer 回放 → 写入 SessionMessageEntity）
3. 如果事件同步成功，直接返回
4. 如果事件同步失败（如 bridge 未启用环境变量），回退到旧锚点同步
5. 旧锚点同步产出的数据写入旧表（MessageEntity/PartEntity），由旧渲染器显示

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Ensure event sync engine handles full history correctly

**Files:**
- Verify: `core/data/src/main/java/com/openmate/core/data/sync/EventSyncEngine.kt` (步骤 3 创建)

- [ ] **Step 1: Verify EventSyncEngine.syncSession handles empty local state**

首次进入一个没有任何本地消息的会话时，`/sync/history` 请求体应为 `{}`（空 map），表示所有 aggregate 都从 seq=0 开始拉取。这会返回该会话的完整事件历史。

确认 EventSyncEngine 中：
- 当本地没有任何 aggregate seq 记录时，发送空 map 请求
- 收到的事件全部交给 EventReplayer 处理
- EventReplayer 输出的 SessionMessage 写入 DB
- 处理完成后更新本地 aggregate seq 记录

- [ ] **Step 2: Verify EventSyncEngine handles incremental updates correctly**

当本地已有 aggregate seq 记录时，请求体应为 `{aggregateID: lastKnownSeq}`，只拉取增量事件。

确认 EventSyncEngine 中：
- 从 SyncStateDao 读取本地 aggregate seq
- 请求体只包含已知的 aggregate（seq > 0 的）
- 新出现的 aggregate 不在请求体中，会自动返回其完整历史
- 处理完成后更新 aggregate seq

---

## Task 3: Remove legacy-only code paths when event sync is confirmed working

**This task is deferred until event sync is proven stable in production.**

当事件同步在生产环境验证稳定后，可以：
- 移除旧锚点同步代码
- 移除旧 MessageEntity/PartEntity 相关 DAO
- 移除旧渲染器 (PartRenderer)
- 简化 MessageRepository 的 fallback 逻辑

但当前阶段保留旧代码作为 fallback。

---

## 同步路径总结

```
用户进入会话
  │
  ├── 检查 SessionMessageEntity 是否有数据
  │     ├── 有 → 直接使用（SSE 持续增量更新）
  │     └── 无 → 继续 ↓
  │
  ├── 尝试事件同步 (/sync/history → EventReplayer)
  │     ├── 成功 → 写入 SessionMessageEntity → SSE 持续更新
  │     └── 失败 → 继续 ↓（bridge 未启用环境变量）
  │
  └── Fallback：旧锚点同步 (GET /session/:sessionID/message)
        ├── 成功 → 写入旧表 → 旧渲染器显示
        └── 失败 → 显示空会话（用户可手动触发刷新）
```

**预期路径**：
- Bridge 已启用环境变量（目标场景）：事件同步成功，所有会话数据完整
- Bridge 未启用环境变量（过渡期）：回退到旧锚点同步，降级但可用

---

## Verification

- [ ] `.\gradlew.bat :app:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] 新安装：纯事件同步正常工作
- [ ] 升级用户（bridge 已启用）：事件同步正确加载历史会话消息
- [ ] 升级用户（bridge 未启用）：回退到旧锚点同步，降级但可用
- [ ] 旧会话（环境变量设置前创建）：`/sync/history` 返回完整事件，EventReplayer 正确回放
