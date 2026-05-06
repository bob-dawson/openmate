# Android 2.1: TokenStore + EncryptedSharedPreferences

## 目标

创建 `TokenStore` 类，用 `EncryptedSharedPreferences` 安全存储每个实例的 Bearer token。提供 `get(profileId)` / `set(profileId, token)` / `remove(profileId)` 方法。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `gradle/libs.versions.toml` — 添加 security-crypto 依赖 |
| 修改 | `core/network/build.gradle.kts` — 添加 security-crypto 依赖 |
| 创建 | `core/network/src/main/java/com/openmate/core/network/TokenStore.kt` |
| 修改 | `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt` — 提供 TokenStore |

## 依赖变更

### `gradle/libs.versions.toml`

在 `[versions]` 中添加：

```toml
security-crypto = "1.1.0-alpha06"
```

在 `[libraries]` 中添加：

```toml
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }
```

### `core/network/build.gradle.kts`

在 dependencies 中添加：

```kotlin
implementation(libs.security.crypto)
```

## `TokenStore.kt`

```kotlin
package com.openmate.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "bridge_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(profileId: String): String? {
        return prefs.getString(key(profileId), null)
    }

    fun set(profileId: String, token: String) {
        prefs.edit().putString(key(profileId), token).apply()
    }

    fun remove(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String): String = "token_$profileId"
}
```

## `NetworkModule.kt` 变更

添加 TokenStore 的提供：

```kotlin
@Provides
@Singleton
fun provideTokenStore(@ApplicationContext context: Context): TokenStore {
    return TokenStore(context)
}
```

需要在文件顶部添加 import：

```kotlin
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.openmate.core.network.TokenStore
```

## 注意事项

1. **EncryptedSharedPreferences 初始化**：首次创建时可能较慢（密钥生成），但只在单例创建时发生一次。

2. **Android Keystore 依赖**：`MasterKey` 依赖 Android Keystore，在 API 23+ (minSdk 26) 上可靠运行。

3. **MasterKey 丢失场景**：如果用户清除应用数据或 Keystore 被重置，`EncryptedSharedPreferences` 中的数据将不可读。此时 `get()` 会抛出异常。建议在 `TokenStore` 中捕获并返回 null：

```kotlin
fun get(profileId: String): String? {
    return try {
        prefs.getString(key(profileId), null)
    } catch (_: Exception) {
        null
    }
}
```

4. **与现有 ServerProfile.password 的关系**：Token 存储与 password 是独立的。`password` 字段（HTTP Basic Auth）已废弃，本方案使用 Bearer token 替代。后续步骤中 `password` 字段可保留但不再用于认证。
