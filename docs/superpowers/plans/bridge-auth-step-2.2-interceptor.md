# Android 2.2: Bearer Token 拦截器

## 目标

替换现有 `AuthInterceptor`（HTTP Basic Auth）为新的 `BearerTokenInterceptor`，从 `TokenStore` 动态读取当前 profile 的 Bearer token。将其添加到所有 3 个 OkHttpClient（api, sse, download）。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/AuthInterceptor.kt` — 替换为 BearerTokenInterceptor |
| 修改 | `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt` — 注入拦截器到所有 client |
| 修改 | `core/network/src/test/java/com/openmate/core/network/AuthInterceptorTest.kt` — 更新测试 |

## `AuthInterceptor.kt` — 替换为 `BearerTokenInterceptor`

完全替换文件内容：

```kotlin
package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor(
    private val tokenStore: TokenStore,
    private val activeProfileIdProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val profileId = activeProfileIdProvider()
        val token = profileId?.let { tokenStore.get(it) }

        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        if (response.code == 401 && token != null) {
            tokenStore.remove(profileId!!)
        }

        return response
    }
}
```

### 关键设计点

1. **动态 token 获取**：`activeProfileIdProvider` 是一个 lambda，返回当前活跃的 profile ID。这解决了"单例 OkHttpClient 需要为不同 profile 提供不同 token"的问题。

2. **401 自动清除 token**：收到 401 时自动从 `TokenStore` 中移除失效 token，触发重新配对流程（由上层 ViewModel 处理）。

3. **无 token 时放行**：如果没有活跃 profile 或没有存储的 token，请求不带 Authorization header。配对流程的公开端点（`/pair/request`, `/pair/confirm`, `/status`）不需要 token。

## `NetworkModule.kt` 变更

```kotlin
package com.openmate.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore {
        return TokenStore(context)
    }

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("api")
    fun provideApiOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideBearerTokenInterceptor(
        tokenStore: TokenStore,
        apiClient: OpencodeApiClient,
    ): BearerTokenInterceptor {
        return BearerTokenInterceptor(tokenStore) { apiClient.activeProfileId }
    }

    @Provides
    @Singleton
    fun provideSseClient(@Named("sse") client: OkHttpClient): SseClient {
        return SseClient(client)
    }

    @Provides
    @Singleton
    fun provideOpencodeApiClient(
        @Named("api") client: OkHttpClient,
        @Named("download") downloadClient: OkHttpClient,
    ): OpencodeApiClient {
        return OpencodeApiClient(client, downloadClient)
    }
}
```

### `activeProfileIdProvider` 的来源

`OpencodeApiClient` 需要新增一个 `activeProfileId` 属性，由外部设置：

在 `OpencodeApiClient.kt` 中添加：

```kotlin
@Volatile
var activeProfileId: String? = null
```

在 `InstanceListViewModel.connect()` 和 `ConnectionManager.connect()` 中，连接时设置：

```kotlin
apiClient.activeProfileId = profile.id
```

断开时清除：

```kotlin
apiClient.activeProfileId = null
```

## `AuthInterceptorTest.kt` — 更新测试

替换为 `BearerTokenInterceptorTest.kt`：

```kotlin
package com.openmate.core.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BearerTokenInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun addsBearerHeaderWhenTokenExists() {
        tokenStore.set("profile1", "test-token-123")
        val interceptor = BearerTokenInterceptor(tokenStore) { "profile1" }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-token-123", recorded.getHeader("Authorization"))
    }

    @Test
    fun noHeaderWhenNoActiveProfile() {
        val interceptor = BearerTokenInterceptor(tokenStore) { null }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun noHeaderWhenNoTokenForProfile() {
        val interceptor = BearerTokenInterceptor(tokenStore) { "nonexistent" }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun removesTokenOn401() {
        tokenStore.set("profile1", "bad-token")
        val interceptor = BearerTokenInterceptor(tokenStore) { "profile1" }

        server.enqueue(MockResponse().setResponseCode(401))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        assertNull(tokenStore.get("profile1"))
    }

    private class FakeTokenStore : TokenStore(null!!) {
        private val map = mutableMapOf<String, String>()
        override fun get(profileId: String): String? = map[profileId]
        override fun set(profileId: String, token: String) { map[profileId] = token }
        override fun remove(profileId: String) { map.remove(profileId) }
    }
}
```

**注意**：`FakeTokenStore` 继承 `TokenStore` 需要调整 `TokenStore` 的构造函数或使用接口。更实用的方案：将 `TokenStore` 提取为接口 + 实现，或直接在测试中手写一个简单 store。

### TokenStore 接口抽取（推荐）

将 `TokenStore` 改为接口：

```kotlin
// core/domain/src/main/java/com/openmate/core/domain/repository/TokenRepository.kt
package com.openmate.core.domain.repository

interface TokenRepository {
    fun get(profileId: String): String?
    fun set(profileId: String, token: String)
    fun remove(profileId: String)
}
```

实现留在 `core/network` 中的 `TokenStore`：

```kotlin
class TokenStore(context: Context) : TokenRepository {
    // ... 实现不变
}
```

拦截器依赖 `TokenRepository` 接口而非具体类，方便测试。

## 循环依赖问题

`NetworkModule` 中：
- `BearerTokenInterceptor` 依赖 `TokenStore` + `OpencodeApiClient`
- `OpencodeApiClient` 依赖 `OkHttpClient`
- `OkHttpClient` 依赖 `BearerTokenInterceptor`

这形成循环：`OpencodeApiClient` → `OkHttpClient` → `BearerTokenInterceptor` → `OpencodeApiClient`

**解决方案**：`BearerTokenInterceptor` 不直接依赖 `OpencodeApiClient`，而是依赖 `ActiveProfileProvider` 接口：

```kotlin
// core/domain/src/main/java/com/openmate/core/domain/repository/ActiveProfileProvider.kt
interface ActiveProfileProvider {
    val activeProfileId: String?
}
```

`OpencodeApiClient` 实现此接口：

```kotlin
class OpencodeApiClient(
    private val client: OkHttpClient,
    private val downloadClient: OkHttpClient = client,
    var baseUrl: String = "http://localhost:8080",
) : ActiveProfileProvider {
    override var activeProfileId: String? = null
    // ...
}
```

但 Hilt 中 `OpencodeApiClient` 不是通过接口绑定的，而是直接提供。Interceptor 接受 `ActiveProfileProvider` 参数：

```kotlin
class BearerTokenInterceptor(
    private val tokenStore: TokenRepository,
    private val activeProfileProvider: ActiveProfileProvider,
) : Interceptor
```

Dagger 可以解析这个依赖链：
1. `TokenStore` (无依赖) → 可先创建
2. `OpencodeApiClient` (依赖 OkHttpClient) → 还不能创建
3. `OkHttpClient` (依赖 BearerTokenInterceptor) → 还不能创建
4. `BearerTokenInterceptor` (依赖 TokenStore + ActiveProfileProvider)

实际上还有循环：`OpencodeApiClient` → `OkHttpClient` → `BearerTokenInterceptor` → `ActiveProfileProvider` (= `OpencodeApiClient`)

**最终方案**：使用 `Lazy<ActiveProfileProvider>` 或 `Provider<ActiveProfileProvider>` 打破循环：

```kotlin
class BearerTokenInterceptor(
    private val tokenStore: TokenRepository,
    private val activeProfileProvider: dagger.Lazy<ActiveProfileProvider>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val profileId = activeProfileProvider.get().activeProfileId
        // ...
    }
}
```

或者更简单：直接用 lambda，不通过 Hilt 注入 `ActiveProfileProvider`：

```kotlin
@Provides
@Singleton
fun provideBearerTokenInterceptor(
    tokenStore: TokenRepository,
    apiClient: Provider<OpencodeApiClient>,
): BearerTokenInterceptor {
    return BearerTokenInterceptor(tokenStore) { apiClient.get().activeProfileId }
}
```

用 `Provider<>` 延迟获取 `OpencodeApiClient`，打破循环依赖。
