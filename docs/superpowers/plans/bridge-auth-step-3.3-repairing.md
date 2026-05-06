# Android 3.3: 401 重配对流程

## 目标

当已连接的 Bridge 因为 token 过期或重置而返回 401 时，自动触发重配对流程，引导用户重新完成配对。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/BearerTokenInterceptor.kt` — 401 时清除 token |
| 修改 | `app/src/main/java/com/openmate/app/ConnectionManager.kt` — 检测 401 并触发重配对 |
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt` — 添加重配对状态 |
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/InstanceListScreen.kt` — 显示重配对 UI |
| 修改 | `feature/instance/src/main/res/values/strings.xml` — 添加重配对字符串 |

## 401 检测链

```
BearerTokenInterceptor 检测到 401
  → 从 TokenStore 中移除失效 token
  → ConnectionManager 检测到 SSE 断连 + token 丢失
  → 设置 rePairingRequired 状态
  → InstanceListScreen 显示重配对对话框
  → 用户确认 → 发起 pairRequest + 输入 PIN + pairConfirm
  → 成功后恢复连接
```

## `BearerTokenInterceptor` — 已在 2.2 中实现

2.2 步骤的 `BearerTokenInterceptor` 已包含 401 自动清除 token 的逻辑：

```kotlin
if (response.code == 401 && token != null) {
    tokenStore.remove(profileId!!)
}
```

无需额外修改。

## `ConnectionManager.kt` 变更

### 新增状态

```kotlin
private val _rePairingRequired = MutableStateFlow<ServerProfile?>(null)
val rePairingRequired: StateFlow<ServerProfile?> = _rePairingRequired.asStateFlow()
```

### 检测 401 的时机

SSE 连接在 401 时会被服务端关闭，导致 `ConnectionStatus.ERROR`。但 REST API 的 401 不会触发 SSE 断连。

**方案**：在 SSE 连接失败时检查是否因 token 缺失：

```kotlin
fun connect(profile: ServerProfile) {
    scope.launch {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null
        _activeProfile.value = profile

        dbProvider.setActive(profile.id)
        apiClient.baseUrl = "http://${profile.address}:${profile.port}"
        apiClient.activeProfileId = profile.id

        try {
            val status = apiClient.bridgeStatus()
            if (status.bridge.version.isBlank()) {
                _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
                _errorMessage.value = "Not a Bridge server."
                dbProvider.clearActive()
                _activeProfile.value = null
                apiClient.activeProfileId = null
                return@launch
            }
        } catch (e: AuthException) {
            _rePairingRequired.value = profile
            _connectionStatus.value = ConnectionStatus.ERROR
            _errorMessage.value = "Authentication required"
            return@launch
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
            _errorMessage.value = "Bridge not reachable: ${e.message}"
            dbProvider.clearActive()
            _activeProfile.value = null
            apiClient.activeProfileId = null
            return@launch
        }

        val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
        profileRepository.save(updated)

        try {
            sseEventRepository.connect(profile.address, profile.port, profile.password)
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.ERROR
            _errorMessage.value = e.message ?: "Connection failed"
        }
    }
}
```

关键变更：
1. `apiClient.activeProfileId = profile.id` — 在连接前设置，使 `BearerTokenInterceptor` 能获取正确的 token
2. 捕获 `AuthException` — `bridgeStatus()` 是公开端点不会 401，但未来其他端点可能。如果 SSE 连接因 401 失败，`SseClient` 会抛出异常
3. 断开时清除 `apiClient.activeProfileId = null`

### 新增 `rePair()` 方法

```kotlin
fun rePair(profile: ServerProfile, pin: String, onResult: (Boolean) -> Unit) {
    scope.launch {
        try {
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
            apiClient.activeProfileId = null
            val pairResult = apiClient.bridgePairConfirm(pin)
            tokenStore.set(profile.id, pairResult.token)
            apiClient.activeProfileId = profile.id
            _rePairingRequired.value = null
            sseEventRepository.connect(profile.address, profile.port, profile.password)
            onResult(true)
        } catch (e: Exception) {
            onResult(false)
        }
    }
}
```

### 修改 `disconnect()`

```kotlin
fun disconnect() {
    sseEventRepository.disconnect()
    dbProvider.clearActive()
    _activeProfile.value = null
    _isConnected.value = false
    _connectionStatus.value = ConnectionStatus.DISCONNECTED
    _errorMessage.value = null
    _rePairingRequired.value = null
    apiClient.activeProfileId = null
}
```

### 新增 `startRePairing()` 方法

```kotlin
fun startRePairing() {
    val profile = _activeProfile.value ?: return
    scope.launch {
        try {
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
            apiClient.activeProfileId = null
            apiClient.bridgePairRequest()
        } catch (_: Exception) {}
    }
}
```

## `ConnectionManager` 新增依赖

```kotlin
@Singleton
class ConnectionManager @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenRepository,  // 新增
) {
```

## `InstanceListScreen.kt` — 重配对对话框

当 `ConnectionManager.rePairingRequired` 不为 null 时，显示配对对话框：

```kotlin
val rePairingProfile by connectionManager.rePairingRequired.collectAsState()

if (rePairingProfile != null) {
    RePairingDialog(
        profile = rePairingProfile!!,
        onDismiss = { connectionManager.disconnect() },
        onConfirm = { pin -> connectionManager.rePair(rePairingProfile!!, pin) { success ->
            if (!success) {
                SnackbarHostState.showSnackbar("Pairing failed, please try again")
            }
        }},
    )
}
```

`RePairingDialog` 是一个简单的 `AlertDialog`：

```kotlin
@Composable
fun RePairingDialog(
    profile: ServerProfile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-pairing Required") },
        text = {
            Column {
                Text("The connection to ${profile.name} requires re-pairing. Enter the PIN shown on the Bridge terminal.")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("6-digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pin.length == 6,
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Disconnect")
            }
        },
    )
}
```

### `InstanceListScreen` 需要注入 `ConnectionManager`

当前 `InstanceListScreen` 已通过 `InstanceListViewModel` 间接访问 `ConnectionManager`。但 `rePairingRequired` 是 `ConnectionManager` 的状态，需要直接收集。

**方案**：在 `InstanceListViewModel` 中暴露 `rePairingRequired`：

```kotlin
val rePairingRequired: StateFlow<ServerProfile?> = connectionManager.rePairingRequired
```

或者直接在 Screen 中 `hiltViewModel()` 注入 `ConnectionManager`。

推荐通过 ViewModel 暴露，保持单向数据流。

## `InstanceListViewModel.kt` 变更

```kotlin
val rePairingRequired: StateFlow<ServerProfile?> = connectionManager.rePairingRequired

fun startRePairing() {
    connectionManager.startRePairing()
}

fun rePair(pin: String) {
    val profile = rePairingRequired.value ?: return
    connectionManager.rePair(profile, pin) { success ->
        if (!success) {
            _errorMessage.value = "Pairing failed, please try again"
        }
    }
}

fun dismissRePairing() {
    connectionManager.disconnect()
}
```

## `strings.xml` 新增

```xml
<string name="re_pairing_title">Re-pairing Required</string>
<string name="re_pairing_message">The connection to %1$s requires re-pairing. Enter the PIN shown on the Bridge terminal.</string>
<string name="re_pairing_pin">6-digit PIN</string>
<string name="re_pairing_confirm">Confirm</string>
<string name="re_pairing_disconnect">Disconnect</string>
<string name="re_pairing_failed">Pairing failed, please try again</string>
```

## SSE 401 的处理

SSE 连接通过 `BearerTokenInterceptor` 添加 Bearer token。如果 Bridge 返回 401，SSE 的 `EventSource` 会收到 HTTP 401 响应。

当前 `SseClient` 使用 `client.newCall(request).execute()` 建立连接。如果收到 401：
- `execute()` 不会抛出异常（HTTP 错误不是异常）
- 但 `response.body` 不是 SSE stream，解析会失败
- `SseClient` 会将此视为连接错误

需要检查 `SseClient.establishConnection()` 中的 401 处理：

```kotlin
// 在 SseClient.establishConnection() 中
val response = client.newCall(request).execute()
if (!response.isSuccessful) {
    if (response.code == 401) {
        throw AuthException("SSE authentication failed")
    }
    throw ServerUnavailableException("SSE connection failed: HTTP ${response.code}")
}
```

这样 401 会以 `AuthException` 形式传播到 `ConnectionManager`，触发重配对流程。

## SSE 断连后的 401 检测

如果 SSE 连接已经建立，后续因为 token 失效被服务端关闭（这种情况不太可能，token 验证只在连接建立时发生），`SseClient` 的重连逻辑会尝试重新连接。

重连时如果收到 401，`BearerTokenInterceptor` 会清除 token，`SseClient` 会抛出 `AuthException`。

`SseEventRepositoryImpl` 的 `observeConnectionStatus()` 会报告 `ConnectionStatus.ERROR`，`ConnectionManager` 可以在此时检查 `tokenStore.get(profileId) == null` 来判断是否需要重配对。

### 在 ConnectionManager.init 中添加检查

```kotlin
init {
    scope.launch {
        sseEventRepository.observeConnectionStatus().collect { status ->
            _connectionStatus.value = status
            _isConnected.value = status == ConnectionStatus.CONNECTED
            if (status == ConnectionStatus.CONNECTED) {
                sessionRepository.refreshSessionStatuses()
            }
            if (status == ConnectionStatus.ERROR) {
                val profileId = _activeProfile.value?.id
                if (profileId != null && tokenStore.get(profileId) == null) {
                    _rePairingRequired.value = _activeProfile.value
                }
                _errorMessage.value = "Connection lost"
            }
        }
    }
}
```

这样当 SSE 断连且 token 已被清除时，自动触发重配对。
