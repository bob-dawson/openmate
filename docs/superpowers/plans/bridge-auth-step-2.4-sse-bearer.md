# Android 2.4: SseClient Bearer header

## 目标

让 `SseClient` 在建立 SSE 连接时携带 Bearer token，以通过 Bridge 的认证中间件。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/SseClient.kt` — 连接时添加 token header |

## 当前状态

`SseClient` 使用 `@Named("sse")` OkHttpClient，2.2 步骤已为该 client 添加 `BearerTokenInterceptor`。但 `SseClient.establishConnection()` 中手动构建 `Request`，然后通过 `client.newCall(request).execute()` 执行。

**关键问题**：OkHttp 的 interceptor 链会自动处理 `BearerTokenInterceptor`，所以 `SseClient` **不需要手动添加 Authorization header**。拦截器会根据 `activeProfileId` 从 `TokenStore` 读取 token 并添加到请求中。

## 验证

`SseClient.establishConnection()` 当前代码（第 64-67 行）：

```kotlin
val request = Request.Builder()
    .url("$baseUrl/global/event")
    .get()
    .build()
```

`client.newCall(request).execute()` 会经过 `BearerTokenInterceptor`，自动添加 `Authorization: Bearer <token>` header。

**无需修改 SseClient 代码。**

## 但需要确认一件事

`SseClient.connect()` 接收 `password: String?` 参数但从未使用。随着 Bearer token 机制的引入，`password` 参数不再需要。可以：

1. **保留参数但不使用**（最小改动，向后兼容）
2. **移除参数**（清理代码）

建议先保留，在 3.3 步骤统一清理连接流程时再移除。

## `SseEventRepositoryImpl.connect()` 同理

`SseEventRepositoryImpl.connect()` 调用 `sseClient.connect(address, port, password)`。`password` 参数一路透传但不使用。同样先保留。
