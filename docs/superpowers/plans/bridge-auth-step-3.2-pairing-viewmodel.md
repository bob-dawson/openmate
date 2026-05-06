# Android 3.2: AddInstanceViewModel 配对流程

## 目标

在 `AddInstanceViewModel` 中实现配对流程：当检测到 Bridge 启用认证时，自动发起配对请求，用户输入 PIN 后确认配对，成功后将 token 存入 `TokenStore`。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/AddInstanceViewModel.kt` — 添加配对逻辑 |
| 修改 | `core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt` — BridgeInfo 添加 authEnabled |

## `BridgeDto.kt` 变更

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    val authEnabled: Boolean = false,
)
```

注意：Rust 端序列化用 `auth_enabled`（snake_case），Kotlin 的 `@Serializable` 默认不转换。需要用 `@SerialName`：

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    @SerialName("auth_enabled")
    val authEnabled: Boolean = false,
)
```

## `AddInstanceViewModel.kt` 变更

### 新增依赖

```kotlin
@HiltViewModel
class AddInstanceViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenRepository,  // 新增
) : ViewModel() {
```

### 新增状态

```kotlin
val pin = MutableStateFlow("")
```

### 修改 `testConnection()`

当前逻辑：调用 `bridgeStatus()` → 成功则 `TestResult.Success`，失败则 `TestResult.Error`。

修改后：调用 `bridgeStatus()` → 如果 `authEnabled == true`，自动调用 `pairRequest()` → `TestResult.PairingRequired`。

```kotlin
fun testConnection() {
    viewModelScope.launch {
        _testResult.value = TestResult.Testing
        try {
            val status = withContext(Dispatchers.IO) {
                val portNum = port.value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port")
                val url = "http://${address.value}:$portNum"
                val saved = apiClient.baseUrl
                apiClient.baseUrl = url
                try {
                    apiClient.bridgeStatus()
                } finally {
                    apiClient.baseUrl = saved
                }
            }
            if (status.bridge.version.isBlank()) {
                _testResult.value = TestResult.Error("Not a Bridge server")
            } else if (status.bridge.authEnabled) {
                withContext(Dispatchers.IO) {
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = "http://${address.value}:${port.value}"
                    try {
                        apiClient.bridgePairRequest()
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
                _testResult.value = TestResult.PairingRequired(status)
            } else {
                _testResult.value = TestResult.Success(status)
            }
        } catch (e: Exception) {
            _testResult.value = TestResult.Error(e.message ?: "Connection failed")
        }
    }
}
```

### 新增 `confirmPairing()`

```kotlin
fun confirmPairing() {
    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val portNum = port.value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port")
                val url = "http://${address.value}:$portNum"
                val saved = apiClient.baseUrl
                apiClient.baseUrl = url
                try {
                    apiClient.bridgePairConfirm(pin.value)
                } finally {
                    apiClient.baseUrl = saved
                }
            }
            val profileId = editProfileId ?: UUID.randomUUID().toString()
            tokenStore.set(profileId, result.token)
            _testResult.value = TestResult.PairingSuccess
        } catch (e: AuthException) {
            _testResult.value = TestResult.Error("Pairing not approved or expired")
        } catch (e: Exception) {
            _testResult.value = TestResult.Error(e.message ?: "Pairing failed")
        }
    }
}
```

### 修改 `save()`

配对成功后，`save()` 需要知道 token 已存储在 `TokenStore` 中。不需要在 `ServerProfile` 中保存 token。

但 `save()` 当前会再次调用 `bridgeStatus()` 验证。对于已配对的 Bridge，这次调用会带上 Bearer token（如果 `activeProfileId` 已设置）。但此时 profile 还没保存，`activeProfileId` 不会是当前 profile 的 ID。

**解决方案**：配对成功后的 `save()` 不再重新验证 Bridge，直接保存 profile：

```kotlin
fun save(onSaved: () -> Unit) {
    val portNum = port.value.toIntOrNull()
    if (name.value.isBlank() || address.value.isBlank() || portNum == null || portNum !in 1..65535) {
        return
    }
    viewModelScope.launch {
        _isSaving.value = true
        try {
            if (testResult.value !is TestResult.PairingSuccess) {
                withContext(Dispatchers.IO) {
                    val url = "http://${address.value}:$portNum"
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = url
                    try {
                        val status = apiClient.bridgeStatus()
                        if (status.bridge.version.isBlank()) {
                            throw IllegalStateException("Not a Bridge server")
                        }
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
            }
            val profile = ServerProfile(
                id = editProfileId ?: UUID.randomUUID().toString(),
                name = name.value,
                address = address.value,
                port = portNum,
                password = password.value.ifBlank { null },
                createdAt = editProfileId?.let {
                    profileRepository.getById(it)?.createdAt
                } ?: System.currentTimeMillis(),
            )
            profileRepository.save(profile)
            withContext(Dispatchers.Main) { onSaved() }
        } catch (e: Exception) {
            _testResult.value = TestResult.Error("Save failed: ${e.message}")
        } finally {
            _isSaving.value = false
        }
    }
}
```

### `confirmPairing` 中 profileId 的问题

`confirmPairing()` 中用 `editProfileId ?: UUID.randomUUID().toString()` 生成 profileId 来存储 token。但 `save()` 中也用同样的逻辑生成 profileId。如果两个方法各自生成不同的 UUID，token 就无法关联到正确的 profile。

**解决方案**：在 ViewModel 中预生成 profileId：

```kotlin
private var pendingProfileId: String? = null

private fun ensureProfileId(): String {
    if (editProfileId != null) return editProfileId!!
    if (pendingProfileId == null) {
        pendingProfileId = UUID.randomUUID().toString()
    }
    return pendingProfileId!!
}
```

在 `confirmPairing()` 和 `save()` 中都使用 `ensureProfileId()`。

## `apiClient.baseUrl` 临时修改问题

当前 `testConnection()` 和 `confirmPairing()` 都临时修改 `apiClient.baseUrl`。这是线程不安全的（单例 `OpencodeApiClient` 可能被其他协程同时使用）。

这是**已有问题**，不在本步骤中解决。但需要注意：
- 配对流程中的 API 调用都发生在 ViewModel scope 中，不会被并发调用
- `bridgePairRequest()` 和 `bridgePairConfirm()` 都不需要 Bearer token，所以 `activeProfileId` 不会影响它们

## Hilt 注入 `TokenRepository`

`AddInstanceViewModel` 需要注入 `TokenRepository`。确保 Hilt module 中已提供 `TokenRepository` 绑定：

```kotlin
// 在 NetworkModule.kt 中
@Provides
@Singleton
fun provideTokenRepository(tokenStore: TokenStore): TokenRepository = tokenStore
```

或者直接注入 `TokenStore`（它实现了 `TokenRepository`）。
