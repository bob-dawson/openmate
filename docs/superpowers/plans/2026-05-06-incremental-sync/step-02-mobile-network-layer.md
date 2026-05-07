# Step 02: 移动端网络层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 Android core:network 模块中新增 Bridge Sync API 的客户端调用，复用现有 OkHttp + kotlinx.serialization 模式。

**Architecture:** 不用 Retrofit，沿用现有 OpencodeApiClient 的手写 OkHttp 模式。新增 `SyncApiClient` 类封装 5 个 Bridge sync 端点调用。新增对应 DTO。

**Tech Stack:** Kotlin, OkHttp 5.1.0, kotlinx.serialization 1.8.1, Hilt

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`

---

## File Structure

```
core/network/src/main/java/com/openmate/core/network/
├── SyncApiClient.kt              — Bridge sync API 客户端
├── dto/
│   └── SyncDto.kt                — 所有 sync 相关 DTO
└── NetworkModule.kt              — 新增 SyncApiClient 的 Hilt 提供
```

---

## Task 1: 新增 Sync DTO

**Files:**
- Create: `core/network/src/main/java/com/openmate/core/network/dto/SyncDto.kt`

- [ ] **Step 1: 创建 SyncDto.kt**

```kotlin
package com.openmate.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class InitResponseDto(
    val messages: List<SyncMessageDto> = emptyList(),
    val maxSeq: Long? = null,
)

@Serializable
data class SyncMessageDto(
    val id: String = "",
    @SerialName("sessionId") val sessionId: String = "",
    val type: String = "",
    @SerialName("timeCreated") val timeCreated: Long = 0,
    @SerialName("timeUpdated") val timeUpdated: Long = 0,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class EventsResponseDto(
    val events: List<SyncEventDto> = emptyList(),
    val maxSeq: Long? = null,
)

@Serializable
data class SyncEventDto(
    val id: String = "",
    @SerialName("aggregateId") val aggregateId: String = "",
    val seq: Long = 0,
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FullMessageResponseDto(
    val id: String = "",
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionsResponseDto(
    val sessions: List<SyncSessionDto> = emptyList(),
)

@Serializable
data class SyncSessionDto(
    val id: String = "",
    val title: String = "",
    val agent: String? = null,
    val model: JsonObject? = null,
    @SerialName("timeCreated") val timeCreated: Long = 0,
    @SerialName("timeUpdated") val timeUpdated: Long = 0,
    @SerialName("hasEvents") val hasEvents: Boolean = false,
    @SerialName("maxSeq") val maxSeq: Long? = null,
)
```

- [ ] **Step 2: Commit**

```
git add core/network/src/main/java/com/openmate/core/network/dto/SyncDto.kt
git commit -m "feat(network): add sync API DTOs"
```

---

## Task 2: 新增 SyncApiClient

**Files:**
- Create: `core/network/src/main/java/com/openmate/core/network/SyncApiClient.kt`

- [ ] **Step 1: 创建 SyncApiClient**

沿用 OpencodeApiClient 的模式：OkHttp + kotlinx.serialization + withContext(Dispatchers.IO)。

```kotlin
package com.openmate.core.network

import com.openmate.core.network.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

class SyncApiClient @Inject constructor(
    @Named("api") private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var baseUrl: String = ""

    suspend fun init(sessionId: String, limit: Int = 30): InitResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/init?limit=$limit"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<InitResponseDto>(body)
        }

    suspend fun events(sessionId: String, afterSeq: Long): EventsResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/events?afterSeq=$afterSeq"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<EventsResponseDto>(body)
        }

    suspend fun fullMessage(sessionId: String, messageId: String): FullMessageResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/session/$sessionId/message/$messageId/full"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<FullMessageResponseDto>(body)
        }

    suspend fun sessions(): SessionsResponseDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/bridge/sync/sessions"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<SessionsResponseDto>(body)
        }
}
```

- [ ] **Step 2: Commit**

```
git add core/network/src/main/java/com/openmate/core/network/SyncApiClient.kt
git commit -m "feat(network): add SyncApiClient for bridge sync API"
```

---

## Task 3: Hilt DI 注册

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt`

- [ ] **Step 1: 在 NetworkModule 中提供 SyncApiClient**

在现有的 `@Module` 对象中添加：

```kotlin
@Provides
@Singleton
fun provideSyncApiClient(@Named("api") client: OkHttpClient): SyncApiClient {
    return SyncApiClient(client)
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: 编译成功

- [ ] **Step 3: Commit**

```
git add core/network/src/main/java/com/openmate/core/network/NetworkModule.kt
git commit -m "feat(network): register SyncApiClient in Hilt"
```
