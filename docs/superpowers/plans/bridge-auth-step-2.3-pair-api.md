# Android 2.3: 配对 API 方法 (`pairRequest` / `pairConfirm`)

## 目标

在 `OpencodeApiClient` 中添加 Bridge 配对 API 方法，供 Android 端调用配对流程。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt` — 添加配对 DTO |
| 修改 | `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt` — 添加配对方法 |

## `BridgeDto.kt` 新增 DTO

```kotlin
@Serializable
data class PairRequestResponse(
    val pin: String = "",
)

@Serializable
data class PairApproveResponse(
    val approved: Boolean = false,
)

@Serializable
data class PairConfirmResponse(
    val token: String = "",
)

@Serializable
data class PairRequestBody(
    val pin: String,
)
```

## `OpencodeApiClient.kt` 新增方法

在 bridge 方法区域（`bridgeStatus()` 附近）添加：

```kotlin
suspend fun bridgePairRequest(): PairRequestResponse {
    return post("/api/bridge/pair/request", emptyMap<String, String>())
}

suspend fun bridgePairConfirm(pin: String): PairConfirmResponse {
    val body = PairRequestBody(pin)
    val jsonStr = json.encodeToString(PairRequestBody.serializer(), body)
    val requestBody = jsonStr.toRequestBody(jsonMediaType)
    val url = buildUrl("/api/bridge/pair/confirm", emptyMap())
    val request = Request.Builder().url(url).post(requestBody).build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
        val errorBody = response.body?.string() ?: ""
        if (response.code == 401) {
            throw AuthException("Pair not approved or expired: $errorBody")
        }
        throw ServerUnavailableException("Pair confirm failed: HTTP ${response.code}: $errorBody")
    }
    val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
    return json.decodeFromString(responseBody)
}
```

### 注意点

1. **`pairRequest` 用 POST 空 body**：Bridge 端的 `pair_request` handler 不要求 body，只需要 `ConnectInfo` 获取 IP。`post("/api/bridge/pair/request", emptyMap())` 会发送空 JSON body `{}`。

2. **`pairConfirm` 需要手动构建请求**：因为需要传递 `{ "pin": "..." }` body，且需要区分 401（未授权/未批准）和其他错误。

3. **`pairRequest` 不需要 token**：配对端点是公开的，`BearerTokenInterceptor` 在没有 token 时不会添加 Authorization header，这正是期望的行为。

4. **`pairConfirm` 也不需要 token**：配对确认也是公开端点，用 PIN 而非 Bearer token 认证。
