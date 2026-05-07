# 步骤 1：网络层 — 新增 /sync/history API + SyncEvent DTO

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 OpencodeApiClient 中新增 `POST /sync/history` 和 `GET /session/:sessionID/message/:messageID` 两个 API，以及对应的 SyncEvent DTO。

**Architecture:** 遵循现有 OpencodeApiClient 的 OkHttp + kotlinx.serialization 模式，新增 DTO 类和 API 方法。

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization

---

## Files

- Modify: `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`
- Create: `core/network/src/main/java/com/openmate/core/network/dto/SyncEventDto.kt`

---

## Task 1: Create SyncEventDto

**Files:**
- Create: `core/network/src/main/java/com/openmate/core/network/dto/SyncEventDto.kt`

- [ ] **Step 1: Create the DTO file**

`/sync/history` 返回扁平事件数组，每个事件结构：

```json
{
  "id": "evt_001",
  "aggregate_id": "ses_xxx",
  "seq": 1,
  "type": "session.next.step.started.1",
  "data": { ... }
}
```

```kotlin
package com.openmate.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncEventDto(
    val id: String,
    val aggregate_id: String,
    val seq: Int,
    val type: String,
    val data: JsonElement,
)
```

`data` 用 `JsonElement` 而非强类型，因为事件类型众多且 data 结构各异，解析推迟到回放阶段按 `type` 分派。

- [ ] **Step 2: Build and verify no compile errors**

Run: `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Add POST /sync/history API method

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

- [ ] **Step 1: Add the syncHistory method**

在 `OpencodeApiClient` 中添加方法。请求体为 `Map<String, Int>`（sessionId → lastSeq），返回 `List<SyncEventDto>`。

```kotlin
suspend fun syncHistory(lastKnownSeqs: Map<String, Int>): List<SyncEventDto> {
    val body = mapToJson(lastKnownSeqs)
    return postAndGet("/sync/history", body)
}
```

需要新增 `postAndGet` 私有方法（现有 `post` 方法无返回值）：

```kotlin
private suspend inline fun <reified T> postAndGet(path: String, body: JsonElement): T = withContext(Dispatchers.IO) {
    val jsonStr = json.encodeToString(JsonElement.serializer(), body)
    val requestBody = jsonStr.toRequestBody(jsonMediaType)
    val request = Request.Builder().url("$baseUrl$path").post(requestBody).build()
    val response = client.newCall(request).execute()
    handleResponse(response)
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 3: Add GET /session/:sessionID/message/:messageID API method

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

- [ ] **Step 1: Add the getMessage method**

三级回源用，返回单条消息的完整内容（旧模型格式 `MessageWithPartsDto`）。

```kotlin
suspend fun getMessage(sessionID: String, messageID: String): MessageWithPartsDto {
    return get("/session/$sessionID/message/$messageID")
}
```

现有 `get` 方法已是泛型的，直接可用。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 4: Add POST /sync/replay API method（可选）

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

- [ ] **Step 1: Add the syncReplay method**

用于触发服务端从事件重建状态（调试/修复用），请求体为 sessionId。

```kotlin
suspend fun syncReplay(sessionID: String) {
    val body = mapToJson(mapOf("sessionID" to sessionID))
    post("/sync/replay/$sessionID", body)
}
```

注意：当前 `/sync/replay` 路由实际为 `POST /sync/replay/:sessionID`，无请求体。如果路由不需要 body，直接用空 POST：

```kotlin
suspend fun syncReplay(sessionID: String) {
    val requestBody = "{}".toRequestBody(jsonMediaType)
    val request = Request.Builder().url("$baseUrl/sync/replay/$sessionID").post(requestBody).build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
        throw ServerUnavailableException("HTTP ${response.code}")
    }
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Verification

- [ ] `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] SyncEventDto 可反序列化实际 API 响应（手动测试：启动 opencode serve 后调用 `/sync/history`）
