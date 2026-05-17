# 基于 MessageID 时间戳解码处理 revert

## 背景

revert 操作产生 `session.updated`（含 `revert.messageID`）和 `message.removed` 事件。原始实现依赖 Bridge `resolveEvtID` API 将 `msg_xxx` ID 转为 `evt_xxx` entity ID，增加网络开销和延迟。

目标：消除 `resolveEvtID` 依赖，通过本地时间戳计算 + DB 查询确定 entity ID 范围。

## 现状

### revert 流程

1. 用户点击 revert → `revertToMessage()` 直接设 `_sessionRevert.from = targetMsg.id`
2. 发送 `revertSession` API → server 处理 → 返回 `session.updated`（含 `info.revert.messageID`）+ `message.removed` 事件
3. 增量同步收到 `session.updated` → 通过 `extractStoredTs(revertMsgID) + k*MOD` 计算 `fullTs` → `getFirstIdAfterTimeCreated(`**`>=`**`)` 得到目标 entity ID = `fromId` → `getMaxIdGte` 得 `toId` → 写入 `session.revertFrom/revertTo`
4. ViewModel session observer 读到 `session.revert` → 更新 `_sessionRevert`
5. Screen 过滤：`extractMsgTimestamp(it.id) < extractMsgTimestamp(revertFromId)` — 排除目标及之后所有消息
6. `message.removed` 事件 → 读 `session.revertFrom/revertTo` → `deleteRange(fromId, toId)` 删除范围内所有 entity

### 关键设计决策：为什么不用精确匹配删除

`msg_xxx` ID 编码的是**消息创建时间**，而 entity ID (`evt_xxx`) 编码的是**事件创建时间**。两者不一致：

| 消息类型 | msg_xxx 编码的时间 | entity 的 timeCreated |
|---------|-------------------|---------------------|
| 用户消息（本地输入） | = timeCreated | = timeCreated（一致） |
| 助理消息（EventReplayer） | = timeCreated | = 事件写入时间（不一致） |

因此按 `msg_xxx` 解码 `timeCreated` → 精确 match entity 的方式对助理消息失败（约 85% 的实体）。

**方案**：改为范围删除，利用 entity ID (`evt_xxx`) 的 lexicographic 顺序（按创建时间有序）确定范围起止。

## ID 时间戳编码

### 格式

```
{prefix}_{12-hex-chars}{14-base62-chars}
       ^^^^^^^^^^^^
       48 bit 编码
```

### 编码

```
hex_value = timestamp_ms * 4096 + counter
```

### 解码（低 36 bit）

```kotlin
fun extractMsgTimestamp(msgId: String): Long {
    val hex = msgId.split("_")[1].take(12)
    return hex.toLong(16) / 4096L
}
```

结果 = `timeCreated % 2^36`。

### 完整恢复

```
stored_ts = int(hex_12, 16) / 4096   // 低 36 bit
k         = timeCreated / 2^36        // 高位
full_ts   = stored_ts + k * 68719476736
```

### 示例

```
ID:  msg_e2e10c4e4001DTY30kz7WFTZfn
hex: e2e10c4e4001
stored_ts: 0xE2E10C4E4001 / 4096 = 60902393060
timeCreated: 1778889311460

2^36 = 68719476736
k = 1778889311460 / 68719476736 = 25
full_ts = 60902393060 + 25 * 68719476736 = 1778889311460 ✓
```

| 参数 | 值 | 说明 |
|------|------|------|
| `2^36` | 68,719,476,736 ms | 低位存储范围，约 2.18 年 |
| k (2026年) | 25 | 当前 epoch 高位倍数 |

## ID 前缀区分

| 前缀 | 编码的时间 |
|------|-----------|
| `msg_` | 消息创建时间（服务器时间） |
| `evt_` | 事件创建时间（EntityManager/EventReplayer 写入时间） |
| `prt_` | part 创建时间 |
| `ses_` | 降序 ID，不适用此方法 |

## 实际实现

### revert 隐藏（UI 过滤）

- `_sessionRevert.from` = revert 目标消息的 entity ID (`evt_xxx`)
- 过滤：`extractMsgTimestamp(it.id) < extractMsgTimestamp(from)`
- 使用时间戳比较而非字符串 ID 比较，确保排序一致性

### revert 删除（增量同步）

`session.updated` handler：

```kotlin
val storedTs = extractStoredTs(revertMsgID)
val nowK = System.currentTimeMillis() / MOD_2_36
for (k in nowK downTo maxOf(0L, nowK - 1)) {
    val fromId = getFirstIdAfterTimeCreated(sessionId, storedTs + k * MOD_2_36)
    if (fromId != null) {
        toId = getMaxIdGte(sessionId, fromId)
        break
    }
}
updateRevertFields(aggId, revertMsgID, revertPartID, fromId, toId)
```

- `getFirstIdAfterTimeCreated` 使用 `timeCreated >= fullTs`（含 target）返回目标 entity ID
- `getMaxIdGte` 取最后一条 `id >= fromId` 的 entity ID
- 写入 `session.revertFrom`/`revertTo`

`message.removed` handler：

```kotlin
val fromId = session?.revertFrom
val toId = session?.revertTo
if (fromId != null && toId != null) {
    val rangeMessages = getInRange(sessionId, fromId, toId)
    deleteRange(sessionId, fromId, toId)
    for (msg in rangeMessages) emit(Remove(msg.id))
}
updateRevertFields(sessionId, null, null, null, null)
```

`deleteRange` = `DELETE FROM session_message WHERE id >= :fromId AND id <= :toId`

### k 的确定

从当前设备时间：`k = System.currentTimeMillis() / 68719476736`。

fallback 到 `k-1`：若 `getFirstIdAfterTimeCreated(k)` 返回 null（无匹配时间戳），尝试 `k-1`。

### 与原始方案的关键差异

| 方面 | 设计文档设想 | 实际实现 |
|------|-------------|---------|
| 删除方式 | 每条 `messageID` 精确匹配 `timeCreated` | 范围删除（entity ID range） |
| `SessionRevert` | 删除 data class | 保留，用于 UI 过滤/横幅 |
| `revertFrom/revertTo` | 删除 DB 字段 | 保留，作为范围删除起止 |
| `deleteRange/getInRange/getMaxIdGte` | 删除 | 保留，核心删除逻辑 |
| `SessionEntity.revert.messageID` | 删除 | 保留 |
| `resolveEvtID` | 删除 | ✅ 已删除 |
| `getFirstIdAfterTimeCreated` | 不存在（计划用 `getByTimeCreated`） | 新增，`>=` 语义 |

### 为什么不用精确匹配删除

精确匹配 `timeCreated` 对助理消息不可靠——EventReplayer 设置 `timeCreated = 事件写入时间`，与 `msg_xxx` 编码的消息创建时间不一致。用户消息虽一致，但混合场景中必须统一处理。

范围删除解决了这个问题，因为 entity ID 的 lexicographic 顺序与创建顺序一致（`evt_xxx` 编码的是连续的、递增的事件写入时间）。

## 改动范围（实际）

| 文件 | 改动 |
|------|------|
| `SessionDetailViewModel.kt` | 移除 `resolveEvtID` 调用，`revertToLastMessage` 改用 `extractMsgTimestamp` 过滤 |
| `SessionDetailScreen.kt` | 过滤从 `it.id < fromId` 改为 `extractMsgTimestamp(it.id) < extractMsgTimestamp(fromId)` |
| `SessionMessageDao.kt` | 新增 `getFirstIdAfterTimeCreated(`**`>=`**`)`，移除 `getByTimeCreated` |
| `SessionMessageRepositoryImpl.kt` | `session.updated` 用 `extractStoredTs`+k+DB 查询代替 `resolveEvtID`，`message.removed` 保持范围删除 `deleteRange` |
| `sync-debugger/Main.kt` | 同步更新，移除 `resolveEvtID` 预取，改用 `extractStoredTs`+`getFirstIdAfterTimeCreated(`**`>=`**`)`+`getMaxIdGte` |
| `sync-debugger/JdbcDao.kt` | 新增 `getFirstIdAfterTimeCreated`、`getMaxIdGte` |
| `sync-debugger/BridgeClient.kt` | 移除 `resolveEvtId` 方法 |
| `sync-debugger/OutputFormatter.kt` | 移除 `resolveEvtIdMs` |
| `sync-debugger/model/Dtos.kt` | 移除 `ResolveEvtIdResponseDto` |
