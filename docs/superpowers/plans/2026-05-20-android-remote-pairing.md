# Android 远程配对 + 网关 Fallback 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android 客户端扫码配对时，优先尝试局域网直连 Bridge，不通时自动走网关中转，两种路径均能完成配对和后续通信。

**Architecture:**
- Bridge QR 码从 `bid` 改为 `iid`（instance_id），Android 用此值做去重 + 网关路由
- ConnectionManager 先试直连，不通则查询网关状态，在线则切到网关 URL
- 所有 HTTP/SSE 请求通过网关时自动加 `X-Instance-Id` header，网关透传不修改请求体
- 网关仅做透明转发，不改 token 认证逻辑

**Tech Stack:** Rust (Bridge), Kotlin + OkHttp (Android)

---

### Task 1: Bridge — QR 码包含 instance_id

**Files:**
- Modify: `opencode-bridge/src/api/qrcode.rs:40-41`

**分析：** QR URL 当前用 `state.bridge_id`（bridge_config 表），需要改为 `config.gateway.instance_id`（config 表 `auth.instance_id`）。AppState 中已有 `config` 字段。

- [ ] **Step 1: 修改 QR 码生成**

```rust
// qrcode.rs line 40-41
// 改前：
url.push_str("&bid=");
url.push_str(&state.bridge_id);
// 改后：
url.push_str("&iid=");
url.push_str(&state.config.gateway.instance_id);
```

- [ ] **Step 2: 验证编译**

```bash
cd opencode-bridge && cargo check 2>&1 | tail -3
```

Expected: 编译成功

- [ ] **Step 3: 提交**

```bash
git add opencode-bridge/src/api/qrcode.rs && git commit -m "feat: QR 码从 bid 改为 iid（instance_id）"
```

---

### Task 2: Android — 更新 QR 解析，改用 instance_id

**Files:**
- Modify: `feature/instance/src/main/java/com/openmate/feature/instance/QrScanViewModel.kt`

**分析：** `ParsedQrUrl.bridgeId` → `instanceId`，`parseQrUrl()` 从 `iid` 参数取值。`ScanResult.bridgeId` 同步改名。

- [ ] **Step 1: 修改 ParsedQrUrl 和 parseQrUrl**

```kotlin
// ParsedQrUrl 改前：
data class ParsedQrUrl(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val bridgeId: String,
)
// 改后：
data class ParsedQrUrl(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val instanceId: String,
)

// parseQrUrl 改前：
val bridgeId = params["bid"] ?: ""
// 改后：
val instanceId = params["iid"] ?: ""

// ParsedQrUrl 构造改前：
ParsedQrUrl(
    name = ...,
    address = parsed.host,
    port = port,
    scanToken = scanToken,
    bridgeId = bridgeId,
)
// 改后：
ParsedQrUrl(
    name = ...,
    address = parsed.host,
    port = port,
    scanToken = scanToken,
    instanceId = instanceId,
)
```

- [ ] **Step 2: 修改 ScanResult**

```kotlin
// ScanResult 改前：
data class ScanResult(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val token: String,
    val deviceId: String,
    val bridgeId: String,
) : ScanUiState
// 改后：
data class ScanResult(
    val name: String,
    val address: String,
    val port: Int,
    val scanToken: String,
    val token: String,
    val deviceId: String,
    val instanceId: String,
) : ScanUiState
```

- [ ] **Step 3: 更新 handleBarcode 中 ScanResult 构造**

```kotlin
// handleBarcode 内构造 ScanResult 改前：
_scanState.value = ScanResult(
    name = parsed.name,
    address = parsed.address,
    port = parsed.port,
    scanToken = parsed.scanToken,
    token = response.token,
    deviceId = response.deviceId,
    bridgeId = parsed.bridgeId,
)
// 改后：
_scanState.value = ScanResult(
    name = parsed.name,
    address = parsed.address,
    port = parsed.port,
    scanToken = parsed.scanToken,
    token = response.token,
    deviceId = response.deviceId,
    instanceId = parsed.instanceId,
)
```

- [ ] **Step 4: 更新 handleScanComplete 中去重逻辑**

```kotlin
// 改前：用 bridgeId 查找已存在 profile
val existingProfile = if (currentState.bridgeId.isNotEmpty()) {
    profileRepository.getAll().find { it.bridgeId == currentState.bridgeId }
} else { null }
// ServerProfile 构造传入 bridgeId
bridgeId = currentState.bridgeId,

// 改后：用 instanceId 查找
val existingProfile = if (currentState.instanceId.isNotEmpty()) {
    profileRepository.getAll().find { it.instanceId == currentState.instanceId }
} else { null }
// ServerProfile 构造传入 instanceId
instanceId = currentState.instanceId,
```

- [ ] **Step 5: 验证编译**

```bash
cd android && .\gradlew.bat :feature:instance:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

Expected: 无错误

---

### Task 3: Android — ServerProfile 和 Repository 增加 instanceId

**Files:**
- Modify: `core/domain/src/main/java/com/openmate/core/domain/model/ServerProfile.kt`
- Modify: `core/data/src/main/java/com/openmate/core/data/repository/ServerProfileRepositoryImpl.kt`

- [ ] **Step 1: 修改 ServerProfile**

```kotlin
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 4097,
    val bridgeId: String = "",
    val instanceId: String = "",
    val password: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null,
)
```

保留 `bridgeId` 兼容已有数据，新增 `instanceId`。

- [ ] **Step 2: 验证编译**

```bash
cd android && .\gradlew.bat :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

Expected: 无错误

---

### Task 4: Android — GatewayHttpClient + X-Instance-Id Interceptor

**Files:**
- Create: `core/network/src/main/java/com/openmate/core/network/GatewayInterceptor.kt`

**分析：** 需要一个 OkHttp Interceptor，在 gateway 模式下自动为所有请求添加 `X-Instance-Id` header。

- [ ] **Step 1: 创建 GatewayInterceptor**

```kotlin
package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response

class GatewayInterceptor : Interceptor {
    @Volatile
    var instanceId: String? = null
        private set

    @Volatile
    var enabled: Boolean = false

    fun enable(instanceId: String) {
        this.instanceId = instanceId
        this.enabled = true
    }

    fun disable() {
        this.instanceId = null
        this.enabled = false
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled || instanceId == null) return chain.proceed(request)
        val newRequest = request.newBuilder()
            .header("X-Instance-Id", instanceId!!)
            .build()
        return chain.proceed(newRequest)
    }
}
```

- [ ] **Step 2: 注册到 Hilt 模块中（找到 OkHttpClient 提供的地方）**

需要找到 Hilt 中提供 OkHttpClient 的模块。先搜索：

```bash
rg "BearerTokenInterceptor|Provides.*OkHttpClient" D:\openmate\android --include "*.kt" -l
```

确认注入位置后在对应 Module 中添加 `GatewayInterceptor`。

- [ ] **Step 3: 验证编译**

```bash
cd android && .\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

---

### Task 5: Android — ConnectionManager 网关 Fallback

**Files:**
- Modify: `app/src/main/java/com/openmate/app/ConnectionManager.kt`

**分析：** 当前 `connect()` 直接设 `apiClient.baseUrl = "http://{address}:{port}"`。改为：
1. 先试直连 `http://{address}:{port}`
2. 若抛出异常，检查网关 `https://gateway.clawmate.net/api/gateway/status?instance_id={instanceId}`
3. 网关返回 `online: true` 则切到网关 URL

- [ ] **Step 1: 添加网关 URL 常量和实例 ID 追踪**

```kotlin
// ConnectionManager.kt 类级添加
private val gatewayBaseUrl = "https://gateway.clawmate.net"
```

- [ ] **Step 2: 实现网关状态检查方法**

```kotlin
// ConnectionManager.kt 添加
private suspend fun isGatewayOnline(instanceId: String): Boolean {
    return try {
        val url = URL("$gatewayBaseUrl/api/gateway/status?instance_id=$instanceId")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val success = conn.responseCode == 200
        conn.disconnect()
        success
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 3: 修改 connect() 中的 baseUrl 设置**

```kotlin
// 在 connect() 中，将原来的：
apiClient.baseUrl = "http://${profile.address}:${profile.port}"

// 改为：
val directUrl = "http://${profile.address}:${profile.port}"
apiClient.baseUrl = directUrl

var useGateway = false
try {
    val status = apiClient.bridgeStatus()
    // ... 原有逻辑
} catch (e: Exception) {
    // 直连失败，尝试网关
    if (profile.instanceId.isNotEmpty()) {
        if (isGatewayOnline(profile.instanceId)) {
            useGateway = true
            apiClient.baseUrl = gatewayBaseUrl
            // 启用 GatewayInterceptor
            gatewayInterceptor.enable(profile.instanceId)
        }
    }
    if (!useGateway) {
        _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
        _errorMessage.value = "Bridge not reachable: ${e.message}"
        clearConnection()
        return@launch
    }
}
```

注意：需要将 `GatewayInterceptor` 注入到 `ConnectionManager`。

```kotlin
// ConnectionManager 构造参数增加：
private val gatewayInterceptor: GatewayInterceptor,
```

- [ ] **Step 4: 修改 SSE 连接，根据网关模式传不同参数**

```kotlin
// connect() 中原有的：
sseEventRepository.connect(profile.address, profile.port, profile.password)

// 改为：
if (useGateway) {
    sseEventRepository.connectViaGateway(gatewayBaseUrl, profile.instanceId, profile.password)
} else {
    sseEventRepository.connect(profile.address, profile.port, profile.password)
}

// sync SSE 连接（syncSseClient.connect 已接受 baseUrl 参数）
// 现在如果 useGateway，baseUrl 已经是 gatewayBaseUrl，无需额外处理
```

- [ ] **Step 5: disconnect() 中恢复**

```kotlin
// 在 clearConnection() 或 disconnect() 中添加：
gatewayInterceptor.disable()
```

- [ ] **Step 6: 验证编译**

```bash
cd android && .\gradlew.bat :app:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

---

### Task 6: Android — SseEventRepository 支持网关连接

**Files:**
- Modify: `core/domain/src/main/java/com/openmate/core/domain/repository/SseEventRepository.kt`
- Modify: `core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`

**分析：** 通过网关的 SSE 连接需要传 `X-Instance-Id` header。SseClient 目前连接时不设自定义 header。

- [ ] **Step 1: 查看 SseClient.connect 签名，确认如何传入 header**

SseClient 当前 `connect(address, port, password)` 直接构造 OkHttp Request。需要新增一个 `connectViaGateway(baseUrl, instanceId, password)` 方法。

- [ ] **Step 2: 给 SseClient 添加网关连接方法**

```kotlin
// SseClient.kt 新增
fun connectViaGateway(baseUrl: String, instanceId: String, password: String?): Flow<SseData> {
    disconnect()
    _connectionStatus.value = ConnectionStatus.CONNECTING
    currentBaseUrl = baseUrl
    reconnectAttempts = 0
    startGatewayConnection(baseUrl, instanceId)
    return events
}

private fun startGatewayConnection(baseUrl: String, instanceId: String) {
    connectJob?.cancel()
    connectJob = scope.launch {
        establishGatewayConnection(baseUrl, instanceId)
    }
}

private suspend fun establishGatewayConnection(baseUrl: String, instanceId: String) {
    running = true
    val request = Request.Builder()
        .url("$baseUrl/global/event")
        .header("X-Instance-Id", instanceId)
        .get()
        .build()
    // 其余逻辑与 establishConnection 相同...
}
```

为了避免重复代码，提取公共的 `connectInternal(request)` 方法。

- [ ] **Step 3: 更新 SseEventRepository 接口**

```kotlin
// SseEventRepository.kt 新增方法声明：
fun connectViaGateway(baseUrl: String, instanceId: String, password: String?): Flow<SseEvent>
```

- [ ] **Step 4: 更新 SseEventRepositoryImpl 实现**

```kotlin
// SseEventRepositoryImpl.kt 新增：
override fun connectViaGateway(baseUrl: String, instanceId: String, password: String?): Flow<SseEvent> {
    return sseClient.connectViaGateway(baseUrl, instanceId, password).map { data ->
        when (data) {
            is SseData.Event -> parser.parse(data)
            is SseData.Connected -> SseEvent.Connected
            is SseData.Disconnected -> SseEvent.Disconnected
            is SseData.Error -> SseEvent.Error
        }
    }
}
```

- [ ] **Step 5: 验证编译**

```bash
cd android && .\gradlew.bat :core:data:compileDebugKotlin :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

---

### Task 7: Android — SyncSseClient 支持网关 Header

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`

**分析：** SyncSseClient 的 `connect()` 接受 `baseUrl` 并自己构造 Request 添加 `Authorization` header。当通过网关时，baseUrl 已经是网关 URL，只需要额外添加 `X-Instance-Id` header。

- [ ] **Step 1: 添加 instanceId 字段**

```kotlin
// SyncSseClient 类中添加
@Volatile
var instanceId: String? = null
```

- [ ] **Step 2: 在 connect() 中发送请求时添加 header**

```kotlin
// SyncSseClient 的 urlBuilder 构建后，添加
if (instanceId != null) {
    urlBuilder.header("X-Instance-Id", instanceId!!)
}
```

- [ ] **Step 3: ConnectionManager 设置 instanceId**

在调用 `syncSseClient.connect(apiClient.baseUrl)` 之前设置：

```kotlin
syncSseClient.instanceId = if (useGateway) profile.instanceId else null
```

- [ ] **Step 4: 验证编译**

```bash
cd android && .\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:" | Select-String -NotMatch "w:"
```

---

### Task 8: 端到端验证

- [ ] **Step 1: 启动网关和 Bridge**

确保 `gateway.clawmate.net` 运行正常，Bridge 已连接（gateway 日志显示 registered）。

- [ ] **Step 2: 扫码配对测试（局域网路径）**

在 Android 上扫描 Bridge Web UI 显示的 QR 码，确认配对成功。

- [ ] **Step 3: 断网测试（远程配对路径）**

断开 Android 和 Bridge 的局域网连接（如关 WiFi、用另一网络），重新扫描同一 QR 码，确认自动 fallback 到网关并配对成功。
