# 步骤二：Session 模型添加 revert 状态

## 背景

opencode 的 `Session.Info` 有 `revert` 字段，表示当前会话处于回滚状态。Android 需要感知这个状态以显示恢复按钮和 revert 指示条。

当前 `SessionDto` 已有 `SessionRevertDto` 字段，但 `toDomain()` 和 `SessionEntity` 都没有映射。

## 改动清单

### 1. Session domain model 添加 revert

文件：`core/domain/src/main/java/com/openmate/core/domain/model/Session.kt`

```kotlin
data class SessionRevert(
    val messageID: String,
    val partID: String? = null,
)

data class Session(
    ...,
    val revert: SessionRevert? = null,  // 新增
)
```

### 2. SessionDto.toDomain() 映射 revert

文件：`core/network/src/main/java/com/openmate/core/network/dto/SessionDto.kt`

`SessionRevertDto` 已存在（`messageID`, `partID`, `snapshot`, `diff`），只需在 `toDomain()` 中添加：

```kotlin
revert = revert?.let { SessionRevert(messageID = it.messageID ?: "", partID = it.partID) },
```

注意：`snapshot` 和 `diff` 是服务端内部用的，Android 不需要。

### 3. SessionEntity 添加 revert 字段

文件：`core/database/src/main/java/com/openmate/core/database/entity/SessionEntity.kt`

```kotlin
data class SessionEntity(
    ...,
    val revertMessageID: String? = null,  // 新增
    val revertPartID: String? = null,     // 新增
)
```

Entity → Domain：
```kotlin
revert = revertMessageID?.let { SessionRevert(it, revertPartID) },
```

Domain → Entity：
```kotlin
revertMessageID = revert?.messageID,
revertPartID = revert?.partID,
```

### 4. SessionEventHandler 处理 session.updated 中的 revert

文件：`core/data/src/main/java/com/openmate/core/data/sse/SessionEventHandler.kt`

当前 `session.updated` 已更新 SessionEntity，添加 revert 字段后映射自动生效，无需额外改动。

### 5. DB version 递增

文件：`core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`

- `version` 从 18 → 19
- 已有 `fallbackToDestructiveMigration()`，无需写迁移脚本

## 验证方式

1. 编译通过
2. 调用 revert API 后，观察 SessionEntity 中 revertMessageID 是否有值
3. 调用 unrevert 后，revertMessageID 应为 null
