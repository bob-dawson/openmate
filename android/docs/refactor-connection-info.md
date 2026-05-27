# 连接信息管理重构设计

## 问题

当前 `baseUrl` 和 `instanceId` 作为可变字段分散在多个单例中（`OpencodeApiClient.baseUrl`、`GatewayInterceptor.instanceId`），外部多处直接赋值：

- `ConnectionManager.connect()` 设置两者
- `EffectExecutor.setApiClient()` / `clearApiClient()` 再次设置
- `AddInstanceViewModel` / `QrScanViewModel` 临时 save-and-restore 切换
- `ConnectionManager.clearConnection()` 再次清空

导致：
1. 切换实例时旧值残留（端口用错）
2. 多处赋值时序不一致（竞态）
3. 临时切换可能影响其他并发请求
4. 外部可以绕过 ConnectionManager 直接改地址

## 目标

**连接信息的唯一来源是当前激活实例的 profile。外部只调 connect/disconnect，不直接设置地址。**

## 设计

### 核心原则

- `OpencodeApiClient` 和 `GatewayInterceptor` **不持有** baseUrl/instanceId 字段
- 每次网络请求时，从 `ActiveProfileProvider` 动态获取当前实例信息
- `ConnectionManager` 是唯一管理激活实例的地方
- 外部无法直接修改连接信息

### 新增：ActiveProfileProvider

放在 `core/network` 模块（避免循环依赖），提供当前激活 profile 的只读访问：

```kotlin
// core/network/ActiveProfileProvider.kt
interface ActiveProfileProvider {
    fun getActiveProfile(): ServerProfile?
}
```

`ServerProfile` 已在 `core/domain` 中定义，包含 `address`、`port`、`instanceId`。
`core/network` 已经依赖 `core/domain`（通过 `ConnectionRoute`），所以可以引用 `ServerProfile`。

### 修改：OpencodeApiClient

- 删除 `_baseUrl` 字段和 `setConnectionInfo()` / `clearConnectionInfo()`
- 构造函数增加 `ActiveProfileProvider` 参数
- `baseUrl` 变成 computed property，从 provider 动态计算：

```kotlin
val baseUrl: String
    get() {
        val profile = activeProfileProvider.getActiveProfile()
        return if (profile != null) "http://${profile.address}:${profile.port}" else ""
    }
```

- `bridgeScanPairConfirmViaGateway` 等临时切换方法：不再 save-and-restore 全局状态，
  改为用独立的 OkHttpClient 直接构造请求（与 `isGatewayBridgeOnline` 同模式）

### 修改：GatewayInterceptor

- 删除 `_instanceId` 字段和 `setInstanceId()`
- 构造函数增加 `ActiveProfileProvider` 参数
- `intercept()` 中从 provider 动态获取：

```kotlin
override fun intercept(chain: Interceptor.Chain): Response {
    val profile = activeProfileProvider.getActiveProfile()
    val id = profile?.instanceId?.ifBlank { null }
    if (id == null) return chain.proceed(chain.request())
    val request = chain.request().newBuilder()
        .header("X-Instance-Id", id)
        .build()
    return chain.proceed(request)
}
```

### 修改：ConnectionManager

- 实现 `ActiveProfileProvider` 接口
- 删除所有 `apiClient.baseUrl =` / `apiClient.setConnectionInfo()` / `apiClient.clearConnectionInfo()` 调用
- 删除所有 `gatewayInterceptor.instanceId =` / `gatewayInterceptor.setInstanceId()` 调用
- `connect()` 只设 `_activeProfile.value = profile`（provider 自动生效）
- `clearConnection()` 只设 `_activeProfile.value = null`（provider 返回 null）

### 修改：EffectExecutor

- 删除 `setApiClient()` / `clearApiClient()` 方法
- 删除 `ConnEffect.SetApiClient` / `ConnEffect.ClearApiClient`
- `ConnectionActor` 中所有 `onEffect(ConnEffect.SetApiClient)` 和 `onEffect(ConnEffect.ClearApiClient)` 删除

### 修改：SyncSseClient / EffectExecutor

- `ConnEffect.StartSse(baseUrl, instanceId)` → `ConnEffect.StartSse`（无参数，从 provider 获取）
- `ConnEffect.ProbeDirect(address, port)` → `ConnEffect.ProbeDirect`（无参数，从 provider 获取）
- `ConnEffect.ProbeGateway(instanceId)` → `ConnEffect.ProbeGateway`（无参数，从 provider 获取）
- `ConnEffect.StartDirectCheckLoop(address, port)` → `ConnEffect.StartDirectCheckLoop`（无参数）
- `ConnEffect.RestartDirectCheckLoop(address, port)` → `ConnEffect.RestartDirectCheckLoop`（无参数）
- EffectExecutor 中这些方法内部从 `activeProfileProvider` 获取地址

### 修改：AddInstanceViewModel / QrScanViewModel

- 不再 save-and-restore `apiClient.baseUrl`
- 改为用临时 OkHttpClient 直接请求（配对/探测是独立操作，不应影响全局连接状态）

### 修改：SyncDebugController

- `val baseUrl = syncSseConnection.currentBaseUrl ?: apiClient.baseUrl` 
- `apiClient.baseUrl` 现在从 provider 获取，自动正确

### 修改：NetworkModule

- `provideOpencodeApiClient()` 和 `provideGatewayInterceptor()` 注入 `ActiveProfileProvider`
- `GatewayInterceptor` 不再 `@Inject constructor()`，改为通过 `@Provides` 构造

## 不改动的部分

- `SyncApiClient`：`baseUrl` getter 已经是 `opencodeApiClient.baseUrl` 的委托，自动跟随
- `BearerTokenInterceptor`：已经从 `TokenStore` 动态获取 token，无需改动
- `SyncSseClient`：接收 baseUrl 参数用于 SSE 连接，但这个值从 provider 获取后传入

## 文件清单

| 文件 | 改动 |
|------|------|
| `core/network/.../ActiveProfileProvider.kt` | **新建** |
| `core/network/.../GatewayInterceptor.kt` | 删除 instanceId 字段，注入 provider |
| `core/network/.../OpencodeApiClient.kt` | 删除 baseUrl 字段，注入 provider，临时切换方法改用独立 client |
| `core/network/.../NetworkModule.kt` | 注入 provider |
| `app/.../ConnectionManager.kt` | 实现 ActiveProfileProvider，删除手动设置 |
| `app/.../EffectExecutor.kt` | 删除 setApiClient/clearApiClient，从 provider 获取地址 |
| `app/.../ConnectionActor.kt` | 删除 SetApiClient/ClearApiClient effect，简化其他 effect 参数 |
| `app/.../ConnEffect.kt` | 简化 effect 定义 |
| `feature/instance/.../AddInstanceViewModel.kt` | 改用临时 OkHttpClient |
| `feature/instance/.../QrScanViewModel.kt` | 改用临时 OkHttpClient |
| `core/data/.../SyncDebugController.kt` | 无需改动（baseUrl 自动正确） |

## 时序保证

切换实例时：
1. `ConnectionManager.connect(profile)` → `_activeProfile.value = profile`（同步，在主线程）
2. 之后任何网络请求读 `apiClient.baseUrl` → 从 provider 获取新 profile 的地址
3. 之后任何拦截器读 `instanceId` → 从 provider 获取新 profile 的 instanceId
4. 无竞态，因为 `_activeProfile` 是 `MutableStateFlow`，所有读取都在同一值上

断开时：
1. `ConnectionManager.clearConnection()` → `_activeProfile.value = null`
2. 之后任何网络请求读 `apiClient.baseUrl` → 返回空字符串
3. 之后任何拦截器读 `instanceId` → 返回 null
