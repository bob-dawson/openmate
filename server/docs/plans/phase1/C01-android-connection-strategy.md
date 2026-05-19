# C.01: Android ServerProfile 扩展 + ConnectionManager Fallback

> 目标：Android 端支持 remote 类型实例，局域网不可达时 fallback 到网关中转。

## Files

- Modify: `android/app/src/main/java/com/openmate/.../core/domain/model/ServerProfile.kt` (或对应文件)
- Modify: `android/app/src/main/java/com/openmate/.../feature/instance/...` (ConnectionManager / 实例添加)
- 需要确认实际文件路径

## 前置条件

- A.01-A.08 网关已部署并可访问
- B.01-B.03 Bridge 已能连接网关

## Steps

- [ ] **Step 1: 确认 ServerProfile 当前结构**

搜索 `ServerProfile` 的定义文件，记录当前字段。

- [ ] **Step 2: 扩展 ServerProfile**

添加字段：

```kotlin
enum class InstanceType { LAN, REMOTE }

data class ServerProfile(
    val id: String,
    val name: String,
    val address: String,      // LAN: IP 地址, REMOTE: 空
    val port: Int,            // LAN: 端口号, REMOTE: 0
    val token: String,
    val type: InstanceType,   // 新增
    val gatewayUrl: String?,  // REMOTE: 网关地址, LAN: 可选（用于 fallback）
    val instanceId: String,   // 新增：网关路由用
    // ... 现有字段
)
```

- [ ] **Step 3: 更新 Room Entity**

对应的 Entity 和 DAO 需要添加新列。递增数据库版本号。

- [ ] **Step 4: 更新 ConnectionManager fallback 逻辑**

在连接方法中：

```kotlin
suspend fun connect(profile: ServerProfile) {
    when (profile.type) {
        InstanceType.LAN -> {
            // 尝试局域网连接
            val reachable = checkLanReachable(profile.address, profile.port)
            if (reachable) {
                connectDirect(profile)
            } else if (profile.gatewayUrl != null) {
                // 检查网关上 Bridge 是否在线
                val online = checkGatewayBridgeOnline(profile.gatewayUrl, profile.token, profile.instanceId)
                if (online) {
                    connectViaGateway(profile)
                } else {
                    showError("Bridge 离线")
                }
            } else {
                showError("局域网不可达")
            }
        }
        InstanceType.REMOTE -> {
            connectViaGateway(profile)
        }
    }
}

private fun connectDirect(profile: ServerProfile) {
    apiClient.setBaseUrl("http://${profile.address}:${profile.port}")
    // 现有连接逻辑
}

private suspend fun connectViaGateway(profile: ServerProfile) {
    apiClient.setBaseUrl(profile.gatewayUrl!!)
    // 添加 X-Instance-Id header
    apiClient.setExtraHeader("X-Instance-Id", profile.instanceId)
    // 其他连接逻辑不变
}

private suspend fun checkGatewayBridgeOnline(gatewayUrl: String, token: String, instanceId: String): Boolean {
    return try {
        val response = httpClient.get("$gatewayUrl/api/gateway/status?instance_id=$instanceId") {
            header("Authorization", "Bearer $token")
        }
        response.body<StatusResponse>().online
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 5: 更新 OpencodeApiClient**

确保支持 `setBaseUrl()` 动态切换和 `setExtraHeader()` 添加自定义 header（X-Instance-Id）。

如果现有实现已支持动态 baseUrl，则无需改动。只需确认额外 header 的机制。

- [ ] **Step 6: 更新 SseClient**

确保 SSE 连接 URL 也随 baseUrl 变化，并携带 X-Instance-Id header。

- [ ] **Step 7: 局域网恢复探测**

在 ConnectionManager 中添加定期探测任务：

```kotlin
private fun startLanProbe(profile: ServerProfile) {
    if (profile.type != InstanceType.LAN) return
    // 每 60 秒探测一次局域网
    probeJob = coroutineScope.launch {
        while (isActive) {
            delay(60_000)
            if (currentMode == Mode.GATEWAY) {
                val reachable = checkLanReachable(profile.address, profile.port)
                if (reachable) {
                    connectDirect(profile)
                    currentMode = Mode.LAN
                }
            }
        }
    }
}
```

- [ ] **Step 8: 编译验证**

Run: `.\gradlew.bat assembleDebug --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 提交**

```bash
git add android/
git commit -m "feat(android): add remote instance type and gateway fallback connection"
```
