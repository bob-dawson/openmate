# 步骤三：OpencodeApiClient 添加 revert/unrevert API

## 背景

Android 需要调用 opencode 的 revert/unrevert REST API。这两个 API 通过 Bridge proxy fallback 转发，Bridge 无需改动。

## API 规格

| API | 方法 | 路径 | Body | 返回 |
|-----|------|------|------|------|
| Revert | POST | `/session/{sessionID}/revert` | `{ messageID, partID? }` | `Session.Info` |
| Unrevert | POST | `/session/{sessionID}/unrevert` | 无 | `Session.Info` |

## 改动清单

### 1. OpencodeApiClient 添加两个方法

文件：`core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

```kotlin
suspend fun revertSession(sessionID: String, messageID: String, partID: String? = null, directory: String? = null) {
    val body = mutableMapOf<String, String>()
    body["messageID"] = messageID
    partID?.let { body["partID"] = it }
    val params = mutableMapOf<String, String>()
    directory?.let { params["directory"] = it }
    postUnit("/session/$sessionID/revert", body, params)
}

suspend fun unrevertSession(sessionID: String, directory: String? = null) {
    val params = mutableMapOf<String, String>()
    directory?.let { params["directory"] = it }
    postUnit("/session/$sessionID/unrevert", emptyMap<String, String>(), params)
}
```

用 `postUnit` 而非 `post`，因为 Android 不需要解析返回值——revert 状态通过 SSE `session.updated` 事件自动同步到本地。

## 验证方式

1. 编译通过
2. 用 bridge_tool.py 或 curl 确认 API 路径可达：`POST /session/{id}/revert` 和 `/session/{id}/unrevert`
