# Step 05: SSE 适配

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 移动端连接 Bridge `/sync/events` SSE 端点，收到轻量通知后触发增量同步。

**Architecture:** 新增 `SyncSseClient` 连接 Bridge 的 `/api/bridge/sync/events`。收到 `{sessionID, seq}` 通知后，与本地 `sync_state.lastSeq` 比较，触发 `SyncOrchestrator.incrementalSync()`。

**Tech Stack:** Kotlin, OkHttp SSE, kotlinx.coroutines, Hilt

---

## File Structure

```
core/network/src/main/java/com/openmate/core/network/
├── SyncSseClient.kt              — Bridge sync SSE 客户端
└── NetworkModule.kt              — 新增 SyncSseClient 的 Hilt 提供

core/data/src/main/java/com/openmate/core/data/
├── sync/
│   └── SyncSseHandler.kt         — SSE 通知→增量同步的协调器
```

---

## Task 1: SyncSseClient

**Files:**
- Create: `core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Modify: `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt`

- [ ] **Step 1: 创建 SyncSseClient**

模式参考现有 `SseClient.kt`，但连接 Bridge 的 `/api/bridge/sync/events` 而非 opencode 的 `/global/event`。通知格式是简单的 `{sessionID, seq}` JSON。

```kotlin
package com.openmate.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Named

data class SyncNotification(
    val sessionId: String,
    val seq: Long,
)

class SyncSseClient @Inject constructor(
    @Named("sse") private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<SyncNotification> = _notifications

    private var currentBaseUrl: String? = null

    suspend fun connect(baseUrl: String) {
        if (currentBaseUrl == baseUrl) return
        disconnect()
        currentBaseUrl = baseUrl
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/events"
            val request = Request.Builder().url(url).get().build()
            try {
                val response = client.newCall(request).execute()
                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@withContext))
                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val data = trimmed.removePrefix("data:").trim()
                            val jsonObj = json.parseToJsonElement(data).jsonObject
                            val sessionId = jsonObj["sessionID"]?.jsonPrimitive?.contentOrNull ?: continue
                            val seq = jsonObj["seq"]?.jsonPrimitive?.longOrNull ?: continue
                            _notifications.tryEmit(SyncNotification(sessionId, seq))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun disconnect() {
        currentBaseUrl = null
    }
}
```

- [ ] **Step 2: 在 NetworkModule 中注册**

```kotlin
@Provides
@Singleton
fun provideSyncSseClient(@Named("sse") client: OkHttpClient): SyncSseClient {
    return SyncSseClient(client)
}
```

- [ ] **Step 3: Commit**

```
git add core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt core/network/src/main/java/com/openmate/core/network/NetworkModule.kt
git commit -m "feat(network): add SyncSseClient for bridge sync notifications"
```

---

## Task 2: SyncSseHandler

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`

- [ ] **Step 1: 创建 SyncSseHandler**

```kotlin
package com.openmate.core.data.sync

import com.openmate.core.data.repository.SessionMessageRepositoryImpl
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

class SyncSseHandler @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val repository: SessionMessageRepositoryImpl,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        syncSseClient.notifications
            .onEach { notification ->
                val lastSeq = repository.getLastSeq(notification.sessionId)
                if (lastSeq != null && notification.seq > lastSeq) {
                    scope.launch {
                        repository.incrementalSync(notification.sessionId)
                    }
                }
            }
            .launchIn(scope)
    }
}
```

- [ ] **Step 2: Commit**

```
git add core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt
git commit -m "feat(data): add SyncSseHandler for SSE-driven incremental sync"
```

---

## Task 3: 集成到 ViewModel

- [ ] **Step 1: 在 SessionDetailViewModel 中启动 SSE 监听**

在 `loadSession()` 中：
1. 调用 `syncSseClient.connect(bridgeBaseUrl)` 建立连接
2. 调用 `syncSseHandler.start()` 开始监听
3. 在 ViewModel 的 `onCleared()` 中调用 `syncSseClient.disconnect()`

具体代码在 Step 06 (UI 适配) 中实现。

- [ ] **Step 2: Commit（与 Step 06 合并）**
