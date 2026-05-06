# Android 2.5: ServerProfile 新增 token 字段 + Repository 适配

## 目标

实际上我们决定 **不在 ServerProfile 中存储 token**。Token 通过 `TokenStore`（EncryptedSharedPreferences）独立存储，以 profile ID 为 key。ServerProfile 保持原有结构不变。

## 设计决策

| 方案 | 优点 | 缺点 |
|------|------|------|
| ServerProfile.token 字段 | 简单直接 | Token 与 profile JSON 一起明文存储在 DataStore |
| 独立 TokenStore | 加密存储，安全 | 多一层存储，但更合理 |

**选择 TokenStore 方案**（2.1 已实现），因为：
- Token 是敏感凭证，必须加密存储
- ServerProfile 的 DataStore 不是加密的
- TokenStore 以 profile ID 为 key，已能关联到对应 profile

## 本步骤的实际工作

1. **确认 `ServerProfile.password` 字段的处置**：
   - `password` 原用于 HTTP Basic Auth，现在改用 Bearer token
   - **保留字段但不再用于认证**，避免破坏序列化兼容性
   - 后续可在 UI 中移除 password 输入框

2. **确认 `TokenStore` 与 `ServerProfile` 的关联**：
   - `TokenStore.get(profile.id)` 获取 token
   - `TokenStore.set(profile.id, token)` 存储 token
   - 删除 profile 时，需同时调用 `TokenStore.remove(profile.id)`

3. **`ServerProfileRepositoryImpl.delete()` 需清除 token**：

```kotlin
override suspend fun delete(id: String) {
    context.profileDataStore.edit { prefs ->
        val existing = prefs[key]?.toMutableSet() ?: return@edit
        existing.removeIf { json.decodeFromString<ServerProfile>(it).id == id }
        prefs[key] = existing
    }
    databaseFactory.delete(context, id)
    tokenStore.remove(id)  // 新增：清除该 profile 的 token
}
```

`ServerProfileRepositoryImpl` 需要注入 `TokenRepository`：

```kotlin
@Singleton
class ServerProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseFactory: DatabaseFactory,
    private val tokenStore: TokenRepository,  // 新增
) : ServerProfileRepository {
```

## `ServerProfileRepository` 接口

接口不变。`delete()` 的 token 清除是实现细节，不需要在接口中体现。

## 数据迁移

无迁移问题。旧版本的 ServerProfile 没有 token，用户首次连接带认证的 Bridge 时会触发配对流程，token 通过 TokenStore 存储。
