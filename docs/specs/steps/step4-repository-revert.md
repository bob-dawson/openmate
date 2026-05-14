# 步骤四：SessionRepository 添加 revert/unrevert

## 背景

ViewModel 层需要通过 Repository 调用 revert/unrevert API。

## 改动清单

### 1. SessionRepository 接口添加方法

文件：`core/domain/src/main/java/com/openmate/core/domain/repository/SessionRepository.kt`

```kotlin
suspend fun revertSession(sessionID: String, messageID: String, partID: String? = null, directory: String? = null)
suspend fun unrevertSession(sessionID: String, directory: String? = null)
```

### 2. SessionRepositoryImpl 实现

文件：`core/data/src/main/java/com/openmate/core/data/repository/SessionRepositoryImpl.kt`

```kotlin
override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) {
    api.revertSession(sessionID, messageID, partID, directory)
}

override suspend fun unrevertSession(sessionID: String, directory: String?) {
    api.unrevertSession(sessionID, directory)
}
```

API 调用后，SSE `session.updated` 事件会触发 SessionEventHandler 更新本地 SessionEntity（步骤二添加 revert 字段后自动生效）。无需手动刷新。

### 3. 更新测试 Fake

涉及 `SessionDetailViewModelTest` 中的 FakeSessionRepository 等，添加空实现：

```kotlin
override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) = Unit
override suspend fun unrevertSession(sessionID: String, directory: String?) = Unit
```

## 验证方式

1. 编译通过
2. 单元测试通过
