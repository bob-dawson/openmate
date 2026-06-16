# App 自更新 + 版本基础设施 实施计划 (Plan 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Android 客户端的 App 自更新（检查版本 → 下载 APK → 安装），并建立两端共用的版本发现基础设施（`version.json` + jsDelivr/raw 双源 VersionClient）。

**Architecture:** 仓库根 `version.json` 按模块（android/bridge）手动维护发布版本；Android 端 VersionClient 经 jsDelivr CDN（优先）/ raw.githubusercontent.com（回退）查询该文件取 android 模块的 `tag`，构造 release asset 下载 URL。设置页新增"App 客户端"更新卡片，镜像现有 opencode 升级卡片样式，复用已有的 `installApk()` / FileProvider / `downloadClient` 模式。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp, kotlinx.serialization, GitHub Actions, jsDelivr CDN

**设计文档:** `docs/superpowers/specs/2026-06-16-auto-update-design.md`

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `version.json`（仓库根） | 创建（手动维护） | 按模块记录发布版本（android/bridge 各自 version/tag） |
| `android/core/common/.../AppInfo.kt` | 创建 | 统一 app versionName 读取 |
| `android/core/network/.../dto/VersionDto.kt` | 创建 | VersionManifest + ModuleVersion |
| `android/core/network/.../ReleaseAssets.kt` | 创建 | tag → release asset URL 构造（纯函数） |
| `android/core/network/.../VersionClient.kt` | 创建 | 查 version.json（jsDelivr/raw）取 android 模块 + 下载 release asset |
| `android/core/network/.../NetworkModule.kt` | 修改 | 新增 @Named("version") + @Named("release") client + VersionClient |
| `android/feature/settings/.../SettingsViewModel.kt` | 修改 | appUpdateInfo + checkAppUpdate + downloadAndInstallApp |
| `android/feature/session/.../WorkspaceListScreen.kt` | 修改 | SettingsContent 新增 App 更新卡片 |
| `android/feature/session/.../strings.xml` | 修改 | App 更新相关字符串 |

**测试文件：**
- `core/network/src/test/.../ReleaseAssetsTest.kt`
- `core/network/src/test/.../VersionClientTest.kt`

---

## Task 1: version.json（手动维护，多模块）

**Files:**
- Create: `version.json`（仓库根）

- [ ] **Step 1: 创建初始 version.json**

仓库根创建 `version.json`，按模块分别记录当前已发布版本（对应 v0.1.19）：

```json
{
  "android": {"version": "0.1.19", "tag": "v0.1.19", "releasedAt": "2026-06-16"},
  "bridge": {"version": "0.1.19", "tag": "v0.1.19", "releasedAt": "2026-06-16"}
}
```

> **手动维护规则**：此文件不由 release workflow 自动更新。每次发版前，开发者根据各模块是否有实质变更，手动更新对应模块的 `version`/`tag`/`releasedAt`。未变更的模块保持上一版本。

- [ ] **Step 2: Commit**

```bash
git add version.json
git commit -m "feat: 添加 version.json（按模块记录发布版本）"
```

---

## Task 2: ReleaseAssets URL 构造工具

**Files:**
- Create: `android/core/network/src/main/java/com/openmate/core/network/ReleaseAssets.kt`
- Test: `android/core/network/src/test/java/com/openmate/core/network/ReleaseAssetsTest.kt`

- [ ] **Step 1: 写失败测试**

`ReleaseAssetsTest.kt`：

```kotlin
package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseAssetsTest {

    @Test
    fun apkUrl_constructsCorrectUrl() {
        val url = ReleaseAssets.apkUrl("v0.1.20")
        assertThat(url).isEqualTo(
            "https://github.com/bob-dawson/openmate/releases/download/v0.1.20/OpenMate-0.1.20.apk"
        )
    }

    @Test
    fun apkFilename_constructsCorrectName() {
        assertThat(ReleaseAssets.apkFilename("v0.1.20")).isEqualTo("OpenMate-0.1.20.apk")
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :core:network:testReleaseUnitTest --tests "com.openmate.core.network.ReleaseAssetsTest"`（或通过 GradleMcp HTTP 接口：`Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":core:network:testReleaseUnitTest","--tests","com.openmate.core.network.ReleaseAssetsTest"],"cwd":"D:\\openmate\\android"}'`）

Expected: FAIL（ReleaseAssets 未定义）

> **注意**：Android 构建必须通过 GradleMcp HTTP 接口（端口 5099），不能用 `gradlew`（Gradle daemon 与 opencode shell 不兼容，见 AGENTS.md）。后续所有 gradle 命令同理。

- [ ] **Step 3: 实现 ReleaseAssets**

`ReleaseAssets.kt`：

```kotlin
package com.openmate.core.network

object ReleaseAssets {
    private const val BASE_URL = "https://github.com/bob-dawson/openmate/releases/download"

    fun apkFilename(tag: String): String {
        val version = tag.trimStart('v')
        return "OpenMate-$version.apk"
    }

    fun apkUrl(tag: String): String = "$BASE_URL/$tag/${apkFilename(tag)}"
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: 通过 GradleMcp 跑同一测试
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/ReleaseAssets.kt android/core/network/src/test/java/com/openmate/core/network/ReleaseAssetsTest.kt
git commit -m "feat(network): 添加 ReleaseAssets release asset URL 构造工具"
```

---

## Task 3: AppInfo 工具（统一 versionName 读取）

**Files:**
- Create: `android/core/common/src/main/java/com/openmate/core/common/AppInfo.kt`

> 现有 3 处重复读取 versionName（WorkspaceListScreen.kt:756、CrashHandler.kt:56、build.gradle.kts:34）。此 task 统一封装，供 App 更新功能使用。后续可渐进替换旧代码（不在本 plan 范围）。

- [ ] **Step 1: 实现 AppInfo**

`AppInfo.kt`：

```kotlin
package com.openmate.core.common

import android.content.Context

object AppInfo {
    fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
}
```

- [ ] **Step 2: 验证编译通过**

Run: 通过 GradleMcp `{"args":[":core:common:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

> AppInfo 依赖 Context，单元测试需 Robolectric。此工具极简（一行逻辑），暂不写单测；后续 ViewModel 测试会间接覆盖。

- [ ] **Step 3: Commit**

```bash
git add android/core/common/src/main/java/com/openmate/core/common/AppInfo.kt
git commit -m "feat(common): 添加 AppInfo 统一 versionName 读取"
```

---

## Task 4: LatestVersionDto + VersionClient

**Files:**
- Create: `android/core/network/src/main/java/com/openmate/core/network/dto/VersionDto.kt`
- Create: `android/core/network/src/main/java/com/openmate/core/network/VersionClient.kt`
- Test: `android/core/network/src/test/java/com/openmate/core/network/VersionClientTest.kt`

- [ ] **Step 1: 创建 VersionDto**

`dto/VersionDto.kt`：

```kotlin
package com.openmate.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class VersionManifest(
    val android: ModuleVersion? = null,
    val bridge: ModuleVersion? = null,
)

@Serializable
data class ModuleVersion(
    val version: String,
    val tag: String,
    val releasedAt: String? = null,
)
```

- [ ] **Step 2: 写失败测试（fetchLatestVersion + jsDelivr/raw 回退 + downloadReleaseAsset）**

`VersionClientTest.kt`：

```kotlin
package com.openmate.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class VersionClientTest {

    private lateinit var jsdelivrServer: MockWebServer
    private lateinit var rawServer: MockWebServer
    private lateinit var downloadServer: MockWebServer

    @Before
    fun setUp() {
        jsdelivrServer = MockWebServer(); jsdelivrServer.start()
        rawServer = MockWebServer(); rawServer.start()
        downloadServer = MockWebServer(); downloadServer.start()
    }

    @After
    fun tearDown() {
        jsdelivrServer.shutdown(); rawServer.shutdown(); downloadServer.shutdown()
    }

    private fun client(jsdelivrUrl: String, rawUrl: String): VersionClient =
        VersionClient(
            versionClient = OkHttpClient(),
            releaseClient = OkHttpClient(),
            jsdelivrVersionUrl = jsdelivrUrl,
            rawVersionUrl = rawUrl,
        )

    @Test
    fun fetchAndroidVersion_jsdelivrSucceeds_returnsVersion() = runBlocking {
        jsdelivrServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"android":{"version":"0.1.20","tag":"v0.1.20","releasedAt":"2026-06-20"}}""")
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result?.version).isEqualTo("0.1.20")
        assertThat(rawServer.requestCount).isEqualTo(0) // 未回退
    }

    @Test
    fun fetchAndroidVersion_jsdelivrFails_fallsBackToRaw() = runBlocking {
        jsdelivrServer.enqueue(MockResponse().setResponseCode(500))
        rawServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"android":{"version":"0.1.20","tag":"v0.1.20"}}""")
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result?.version).isEqualTo("0.1.20")
    }

    @Test
    fun fetchAndroidVersion_bothFail_returnsNull() = runBlocking {
        jsdelivrServer.enqueue(MockResponse().setResponseCode(500))
        rawServer.enqueue(MockResponse().setResponseCode(500))
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val result = c.fetchAndroidVersion()
        assertThat(result).isNull()
    }

    @Test
    fun downloadReleaseAsset_writesFileAndReportsProgress() = runBlocking {
        val bytes = ByteArray(100) { it.toByte() }
        downloadServer.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes))
        )
        val c = client(jsdelivrServer.url("/").toString(), rawServer.url("/").toString())
        val dest = File.createTempFile("test", ".apk").apply { delete() }
        var lastDownloaded = 0L
        c.downloadReleaseAsset(
            url = downloadServer.url("/asset").toString(),
            destFile = dest,
            onProgress = { downloaded, _ -> lastDownloaded = downloaded }
        )
        assertThat(dest.exists()).isTrue()
        assertThat(dest.length()).isEqualTo(100L)
        assertThat(lastDownloaded).isAtLeast(100L)
        dest.delete()
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: 通过 GradleMcp `{"args":[":core:network:testReleaseUnitTest","--tests","com.openmate.core.network.VersionClientTest"],"cwd":"D:\\openmate\\android"}`
Expected: FAIL（VersionClient 未定义）

- [ ] **Step 4: 实现 VersionClient**

`VersionClient.kt`：

```kotlin
package com.openmate.core.network

import com.openmate.core.network.dto.ModuleVersion
import com.openmate.core.network.dto.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

class VersionClient @Inject constructor(
    @param:Named("version") private val versionClient: OkHttpClient,
    @param:Named("release") private val releaseClient: OkHttpClient,
    private val jsdelivrVersionUrl: String = DEFAULT_JSDELIVR_URL,
    private val rawVersionUrl: String = DEFAULT_RAW_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAndroidVersion(): ModuleVersion? = withContext(Dispatchers.IO) {
        fetchManifest(jsdelivrVersionUrl)?.android ?: fetchManifest(rawVersionUrl)?.android
    }

    private fun fetchManifest(url: String): VersionManifest? = runCatching {
        val request = Request.Builder().url(url).get().build()
        versionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            json.decodeFromString<VersionManifest>(body)
        }
    }.getOrNull()

    suspend fun downloadReleaseAsset(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val response = releaseClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
        }
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val contentLength = body.contentLength()
        destFile.parentFile?.mkdirs()
        body.byteStream().buffered().use { input ->
            destFile.outputStream().buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastProgressTime = System.currentTimeMillis()
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastProgressTime >= 1000) {
                        onProgress.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
                        lastProgressTime = now
                    }
                }
                output.flush()
                onProgress?.invoke(downloaded, if (contentLength > 0) contentLength else downloaded)
            }
        }
    }

    companion object {
        const val DEFAULT_JSDELIVR_URL =
            "https://cdn.jsdelivr.net/gh/bob-dawson/openmate@main/version.json"
        const val DEFAULT_RAW_URL =
            "https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json"
    }
}
```

> 说明：构造参数 `jsdelivrVersionUrl` / `rawVersionUrl` 带默认值（生产用），测试时注入 MockWebServer URL。生产中 NetworkModule 用无参构造（走默认 URL）。

- [ ] **Step 5: 运行测试确认通过**

Run: 通过 GradleMcp 跑 VersionClientTest
Expected: 4 个测试全 PASS

- [ ] **Step 6: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/dto/VersionDto.kt android/core/network/src/main/java/com/openmate/core/network/VersionClient.kt android/core/network/src/test/java/com/openmate/core/network/VersionClientTest.kt
git commit -m "feat(network): 添加 VersionClient（jsDelivr/raw 双源版本查询 + release 下载）"
```

---

## Task 5: NetworkModule DI

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/NetworkModule.kt`（在 provideOpencodeApiClient 之后，约 line 85）

- [ ] **Step 1: 新增 @Named("version") + @Named("release") client 与 VersionClient provider**

在 `NetworkModule` object 内（`provideOpencodeApiClient` 之后）追加：

```kotlin
    @Provides
    @Singleton
    @Named("version")
    fun provideVersionOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("release")
    fun provideReleaseOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideVersionClient(
        @Named("version") versionClient: OkHttpClient,
        @Named("release") releaseClient: OkHttpClient,
    ): VersionClient {
        return VersionClient(versionClient, releaseClient)
    }
```

> 注意：不加 `BearerTokenInterceptor` / `GatewayInterceptor`（访问公网 jsDelivr/raw/github.com，不该带 bridge token）。

- [ ] **Step 2: 确认 import**

确保文件顶部有 `import com.openmate.core.network.VersionClient`（VersionClient 在同包 `com.openmate.core.network`，通常无需显式 import，但确认包名一致）。

- [ ] **Step 3: 验证编译**

Run: 通过 GradleMcp `{"args":[":core:network:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/NetworkModule.kt
git commit -m "feat(network): NetworkModule 添加 version/release client 与 VersionClient provider"
```

---

## Task 6: SettingsViewModel 扩展（App 更新检查 + 下载安装）

**Files:**
- Modify: `android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt`
- Modify: `android/feature/settings/build.gradle.kts`（补 robolectric 测试依赖）

> feature/settings 需依赖 `:core:network`（VersionClient）和 `:core:common`（AppInfo）。确认 `feature/settings/build.gradle.kts` 已 `implementation(project(":core:network"))` 与 `implementation(project(":core:common"))`；若无则补。

- [ ] **Step 1: 补 feature/settings 测试依赖（如缺）**

检查 `feature/settings/build.gradle.kts`，若无 robolectric 则补：

```kotlin
testImplementation(libs.robolectric)
```

并确认已有 `testImplementation(libs.truth)` 与 `testImplementation(libs.kotlinx.coroutines.test)`。

- [ ] **Step 2: SettingsViewModel 新增依赖与 AppUpdateInfo 数据类**

在 `SettingsViewModel.kt`：

构造参数新增 `versionClient`（在 `apiClient` 之后）：

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileRepository: ServerProfileRepository,
    private val connectionRepository: ConnectionRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val versionClient: VersionClient,
) : ViewModel() {
```

文件内（class 顶部 StateFlow 区，约 line 73 后）新增：

```kotlin
    data class AppUpdateInfo(
        val currentVersion: String,
        val latestVersion: String?,
        val hasUpdate: Boolean,
    )

    data class AppDownloadState(
        val isDownloading: Boolean = false,
        val progress: Int = 0,        // 0..100
        val error: String? = null,
    )

    private val _appUpdateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val appUpdateInfo: StateFlow<AppUpdateInfo?> = _appUpdateInfo.asStateFlow()

    private val _appDownloadState = MutableStateFlow(AppDownloadState())
    val appDownloadState: StateFlow<AppDownloadState> = _appDownloadState.asStateFlow()

    private var latestModuleVersion: ModuleVersion? = null
```

新增 import：

```kotlin
import com.openmate.core.common.AppInfo
import com.openmate.core.common.FileOpener
import com.openmate.core.network.ReleaseAssets
import com.openmate.core.network.VersionClient
import com.openmate.core.network.dto.ModuleVersion
```

- [ ] **Step 3: 实现 checkAppUpdate() 与 init 调用**

在 `checkVersion()` 旁新增：

```kotlin
    fun checkAppUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latest = versionClient.fetchAndroidVersion()
                latestModuleVersion = latest
                val current = AppInfo.versionName(appContext)
                val hasUpdate = latest != null && isNewer(latest.version, current)
                _appUpdateInfo.value = AppUpdateInfo(
                    currentVersion = current,
                    latestVersion = latest?.version,
                    hasUpdate = hasUpdate,
                )
            } catch (_: Exception) {
                // 静默失败，UI 显示"检查失败可重试"或保持空
            }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val b = current.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }
```

在 `init {}` 块追加 `checkAppUpdate()`：

```kotlin
    init {
        loadActiveProfile()
        refreshCacheInfo()
        checkVersion()
        checkAppUpdate()
    }
```

- [ ] **Step 4: 实现 downloadAndInstallApp()**

```kotlin
    fun downloadAndInstallApp() {
        val info = _appUpdateInfo.value ?: return
        val tag = latestModuleVersion?.tag ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_appDownloadState.value.isDownloading) return@launch
            _appDownloadState.value = AppDownloadState(isDownloading = true)
            try {
                val url = ReleaseAssets.apkUrl(tag)
                val destDir = File(appContext.cacheDir, "file_cache")
                val destFile = File(destDir, ReleaseAssets.apkFilename(tag))
                versionClient.downloadReleaseAsset(
                    url = url,
                    destFile = destFile,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            _appDownloadState.value = _appDownloadState.value.copy(
                                progress = ((downloaded * 100) / total).toInt(),
                            )
                        }
                    },
                )
                _appDownloadState.value = AppDownloadState(isDownloading = false, progress = 100)
                FileOpener.installApk(appContext, destFile, destFile.name)
            } catch (e: Exception) {
                _appDownloadState.value = AppDownloadState(
                    error = e.message ?: "下载失败，请前往 GitHub Releases 手动下载",
                )
            }
        }
    }

    fun clearAppDownloadError() {
        _appDownloadState.value = AppDownloadState()
    }
```

- [ ] **Step 5: 验证编译**

Run: 通过 GradleMcp `{"args":[":feature:settings:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt android/feature/settings/build.gradle.kts
git commit -m "feat(settings): SettingsViewModel 添加 App 更新检查与下载安装逻辑"
```

---

## Task 7: 设置页 UI（App 更新卡片 + 字符串）

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt`（SettingsContent，Opencode Management 卡片之后 / About 之前，约 line 744-746 之间）
- Modify: `android/feature/session/src/main/res/values/strings.xml`

- [ ] **Step 1: 新增字符串资源**

在 `feature/session/src/main/res/values/strings.xml`（约 opencode 版本字符串区，line 228-241 附近）追加：

```xml
    <string name="app_update">App Update</string>
    <string name="app_latest_version">Latest Version</string>
    <string name="app_current_version">Current Version</string>
    <string name="app_up_to_date">Up to date</string>
    <string name="download_and_install">Download &amp; Install</string>
    <string name="downloading">Downloading…</string>
    <string name="app_download_failed">Download failed. Please download manually from GitHub Releases.</string>
    <string name="check_failed_retry">Check failed, retry</string>
```

- [ ] **Step 2: 在 SettingsContent 新增 App 更新卡片**

在 `WorkspaceListScreen.kt` 的 `SettingsContent`，**Opencode Management 的 `item { ... }`（约 line 744 `}`）之后、About 的 `item { ... }`（约 line 746）之前**，插入新 `item { ... }`：

```kotlin
        item {
            val appUpdateInfo by viewModel.appUpdateInfo.collectAsState()
            val appDownloadState by viewModel.appDownloadState.collectAsState()

            SectionHeader(title = stringResource(R.string.app_update))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.app_latest_version),
                    subtitle = null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    appDownloadState.isDownloading -> stringResource(R.string.downloading)
                                    appUpdateInfo?.latestVersion != null -> "v${appUpdateInfo?.latestVersion}"
                                    else -> stringResource(R.string.check_failed_retry)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (appUpdateInfo?.hasUpdate == true)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    enabled = !appDownloadState.isDownloading,
                                    onClick = { viewModel.checkAppUpdate() }
                                ),
                            )
                        }
                    },
                )
                if (appUpdateInfo?.hasUpdate == true && !appDownloadState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.download_and_install),
                        subtitle = null,
                        showDivider = appDownloadState.error != null,
                        modifier = Modifier.clickable { viewModel.downloadAndInstallApp() },
                        trailing = {
                            Text(
                                text = "\u203A",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                if (appDownloadState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.downloading),
                        subtitle = "${appDownloadState.progress}%",
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(
                                progress = { appDownloadState.progress / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                } else if (appDownloadState.error != null) {
                    SettingsRow(
                        title = stringResource(R.string.app_download_failed),
                        subtitle = null,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.clearAppDownloadError() },
                        trailing = {
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (appUpdateInfo?.hasUpdate == false) {
                    SettingsRow(
                        title = stringResource(R.string.app_current_version),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "v${appUpdateInfo?.currentVersion ?: "?"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
```

- [ ] **Step 3: 确认 import**

确保 `WorkspaceListScreen.kt` 顶部有（多数已存在）：`collectAsState`、`clickable`、`CircularProgressIndicator`、`Alignment`、`Spacer`、`width`/`size`/`height`、`MaterialTheme` 等。若 `CircularProgressIndicator` 未 import，加 `import androidx.compose.material3.CircularProgressIndicator`。

- [ ] **Step 4: 验证编译**

Run: 通过 GradleMcp `{"args":[":feature:session:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt android/feature/session/src/main/res/values/strings.xml
git commit -m "feat(session): 设置页添加 App 更新检查与下载安装卡片"
```

---

## Task 8: 集成验证

- [ ] **Step 1: 整体编译**

Run: 通过 GradleMcp `{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行全部 core/network 测试**

Run: 通过 GradleMcp `{"args":[":core:network:testReleaseUnitTest"],"cwd":"D:\\openmate\\android"}`
Expected: 全部 PASS（含 ReleaseAssetsTest + VersionClientTest + 既有测试）

- [ ] **Step 3: 手动功能验证（安装到设备）**

通过 GradleMcp 构建 debug APK 并安装（`adb install`），打开 App → 设置页：
- 应看到"App Update"卡片，显示当前版本与最新版本（从 version.json 读到 0.1.19）
- 由于当前版本 = 最新版本（0.1.19），应显示"Up to date"或当前版本
- 点"Check for updates"应刷新

> 完整下载安装验证需要先发布一个更高版本（如 0.1.20）使 version.json 更新，再在旧版本 App 上检查——这属于发布后验证，不在本 plan 实施范围。

- [ ] **Step 4: 最终 Commit（如有 lint/格式调整）**

```bash
git add -A
git commit -m "chore: App 自更新 Plan 1 集成验证通过" --allow-empty
```

---

## Self-Review 检查清单（实施完成后对照）

1. **Spec 覆盖**：version.json 多模块（Task 1）✅、VersionClient jsDelivr/raw（Task 4）✅、URL 构造用 tag（Task 2）✅、App 下载安装（Task 6/7）✅、设置页入口（Task 7）✅
2. **类型一致**：`AppUpdateInfo` / `AppDownloadState` 在 ViewModel 定义，UI 用 `appUpdateInfo` / `appDownloadState` StateFlow 名称一致 ✅
3. **无 placeholder**：每个 step 含实际代码 ✅
4. **测试**：ReleaseAssets（纯函数 tag 参数）+ VersionClient（MockWebServer，含多模块 JSON + jsDelivr/raw 回退 + 下载）✅；ViewModel 因依赖 Context/多 repository 较重，以编译 + 手动验证为主（可选手写 fake 测试留作增强）
5. **手动维护 version.json**：release workflow 不自动更新，开发者发版前按模块实际变更手动更新对应条目 ✅
