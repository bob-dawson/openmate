# opencode 重启与升级功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bridge 新增版本检查和升级 API，Android 设置页提供重启/升级 opencode 操作入口。

**Architecture:** Bridge 在 OpencodeManager 中增加 version 缓存（从 health 响应解析），新增 version 查询和 upgrade 接口。Android 端 OpencodeApiClient 新增对应调用方法，SettingsViewModel 增加状态管理，SettingsContent 新增 UI 区块。

**Tech Stack:** Rust (axum/reqwest/serde/tokio) + Kotlin (Jetpack Compose/Hilt/OkHttp/kotlinx.serialization)

---

## Task 1: Bridge — OpencodeManager 增加 version 缓存

**Files:**
- Modify: `opencode-bridge/src/process/opencode_manager.rs`

- [ ] **Step 1: 在 OpencodeManager 结构体中增加 version 字段**

在 `OpencodeManager` 中添加 `opencode_version: Arc<RwLock<Option<String>>>`，在 `new()` 和 `with_config()` 中初始化为 `Arc::new(RwLock::new(None))`。

- [ ] **Step 2: 改造 check_health() 解析 version**

将 `check_health()` 改为解析 `/global/health` 响应体的 `version` 字段并缓存。方法签名保持返回 `bool`：

```rust
pub async fn check_health(&self) -> bool {
    let url = format!("{}/global/health", self.opencode_url);
    match reqwest::get(&url).await {
        Ok(resp) if resp.status().is_success() => {
            if let Ok(body) = resp.json::<serde_json::Value>().await {
                if let Some(version) = body.get("version").and_then(|v| v.as_str()) {
                    let mut cached = self.opencode_version.write().await;
                    *cached = Some(version.to_string());
                }
            }
            true
        }
        _ => false,
    }
}
```

- [ ] **Step 3: 新增 get_cached_version() 方法**

```rust
pub async fn get_cached_version(&self) -> Option<String> {
    self.opencode_version.read().await.clone()
}
```

- [ ] **Step 4: 在 start 的 health 轮询中也缓存 version**

在 `start()` 方法中 health 轮询成功后（第 98-103 行），同样解析并缓存 version：

```rust
if let Ok(resp) = client.get(format!("{}/global/health", url)).send().await {
    if resp.status().is_success() {
        if let Ok(body) = resp.json::<serde_json::Value>().await {
            if let Some(v) = body.get("version").and_then(|v| v.as_str()) {
                let mut ver = version.clone().write().await;
                *ver = Some(v.to_string());
            }
        }
        let mut s = status_arc.write().await;
        *s = OpencodeStatus::Running;
        tracing::info!("opencode is ready");
        break;
    }
}
```

需要在 spawn 的 async block 之前 clone `self.opencode_version`，并在 spawn 闭包中传入 `version` 变量。

- [ ] **Step 5: 新增 get_latest_version() 方法**

```rust
pub async fn get_latest_version(&self) -> Result<String, String> {
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        tokio::process::Command::new("npm")
            .args(["view", "opencode-ai", "version"])
            .output(),
    )
    .await
    .map_err(|_| "npm view timed out (30s)".to_string())?
    .map_err(|e| format!("Failed to run npm view: {}", e))?;

    if !output.status.success() {
        return Err(format!(
            "npm view failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
    }

    let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if version.is_empty() {
        return Err("npm view returned empty version".to_string());
    }
    Ok(version)
}
```

- [ ] **Step 6: 新增 upgrade() 方法**

```rust
pub async fn upgrade(&self) -> Result<UpgradeResult, String> {
    let previous_version = self.get_cached_version().await;

    // Stop opencode
    if self.is_running().await {
        self.stop().await?;
        tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
    }

    // Run npm install
    let npm_result = tokio::time::timeout(
        std::time::Duration::from_secs(300),
        tokio::process::Command::new("npm")
            .args(["install", "-g", "opencode-ai@latest"])
            .output(),
    )
    .await;

    match npm_result {
        Ok(Ok(output)) if output.status.success() => {
            // npm succeeded, start opencode
            self.start().await?;

            // Wait for health check to get new version (start already polls health)
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;

            let new_version = self.get_cached_version().await;
            Ok(UpgradeResult {
                success: true,
                previous_version,
                new_version,
                error: None,
                recovered: None,
                current_version: None,
            })
        }
        Ok(Ok(output)) => {
            let error = String::from_utf8_lossy(&output.stderr).to_string();
            // npm failed, try to recover
            let recovered = self.start().await.is_ok();
            let current_version = if recovered {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                self.get_cached_version().await
            } else {
                None
            };
            Ok(UpgradeResult {
                success: false,
                previous_version,
                new_version: None,
                error: Some(error),
                recovered: Some(recovered),
                current_version,
            })
        }
        Ok(Err(e)) => {
            let error = format!("Failed to run npm install: {}", e);
            let recovered = self.start().await.is_ok();
            Ok(UpgradeResult {
                success: false,
                previous_version,
                new_version: None,
                error: Some(error),
                recovered: Some(recovered),
                current_version: None,
            })
        }
        Err(_) => {
            let error = "npm install timed out (300s)".to_string();
            let recovered = self.start().await.is_ok();
            Ok(UpgradeResult {
                success: false,
                previous_version,
                new_version: None,
                error: Some(error),
                recovered: Some(recovered),
                current_version: None,
            })
        }
    }
}
```

在文件顶部（`use` 区块后）添加 `UpgradeResult` 结构体：

```rust
#[derive(Debug, serde::Serialize)]
pub struct UpgradeResult {
    pub success: bool,
    #[serde(rename = "previousVersion")]
    pub previous_version: Option<String>,
    #[serde(rename = "newVersion")]
    pub new_version: Option<String>,
    pub error: Option<String>,
    pub recovered: Option<bool>,
    #[serde(rename = "currentVersion")]
    pub current_version: Option<String>,
}
```

- [ ] **Step 7: 编译验证**

Run: `cargo build 2>&1` (在 `opencode-bridge` 目录下)
Expected: 编译通过，无错误

- [ ] **Step 8: Commit**

```
git add opencode-bridge/src/process/opencode_manager.rs
git commit -m "feat(bridge): add version cache and upgrade method to OpencodeManager"
```

---

## Task 2: Bridge — 新增 version 和 upgrade API 路由

**Files:**
- Modify: `opencode-bridge/src/bridge/router.rs`
- Modify: `opencode-bridge/src/error.rs`
- Modify: `opencode-bridge/src/server.rs`

- [ ] **Step 1: 在 error.rs 中添加升级相关错误变体**

在 `AppError` 枚举中添加：

```rust
#[error("Upgrade failed: {0}")]
UpgradeFailed(String),

#[error("Upgrade in progress")]
UpgradeInProgress,
```

在 `IntoResponse` 的 match 中添加对应映射：

```rust
AppError::UpgradeFailed(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
AppError::UpgradeInProgress => (StatusCode::CONFLICT, self.to_string()),
```

- [ ] **Step 2: 在 bridge/router.rs 中添加 version handler**

```rust
use crate::process::opencode_manager::UpgradeResult;

pub async fn opencode_version(State(state): State<AppState>) -> impl IntoResponse {
    let current = state.opencode_manager.get_cached_version().await;
    let latest = state.opencode_manager.get_latest_version().await.ok();

    let current_str = current.as_deref().unwrap_or("unknown");
    let latest_str = latest.as_deref().unwrap_or("unknown");

    let has_update = match (&current, &latest) {
        (Some(c), Some(l)) => is_newer_version(l, c),
        _ => false,
    };

    Json(json!({
        "current": current,
        "latest": latest,
        "hasUpdate": has_update,
    }))
}

fn is_newer_version(new: &str, old: &str) -> bool {
    let parse = |v: &str| -> Vec<u32> {
        v.trim_start_matches('v')
            .split('.')
            .filter_map(|s| s.parse().ok())
            .collect()
    };
    let new_parts = parse(new);
    let old_parts = parse(old);
    new_parts > old_parts
}
```

- [ ] **Step 3: 在 bridge/router.rs 中添加 upgrade handler**

需要用 `Arc<RwLock<bool>>` 追踪升级是否正在进行中。先在 `AppStateInner` 中添加字段（或在 router 中用 `State` 里的状态判断）。

更简单的方案：在 `OpencodeManager` 中添加 `upgrade_in_progress` 标志：

在 `OpencodeManager` 中添加字段 `upgrade_in_progress: Arc<AtomicBool>`，初始化为 `Arc::new(AtomicBool::new(false))`。

添加方法：
```rust
pub fn is_upgrade_in_progress(&self) -> bool {
    self.upgrade_in_progress.load(std::sync::atomic::Ordering::Relaxed)
}
```

在 `upgrade()` 方法开头设置标志，结束时清除：

```rust
pub async fn upgrade(&self) -> Result<UpgradeResult, String> {
    if self.upgrade_in_progress.swap(true, std::sync::atomic::Ordering::Relaxed) {
        return Err("Upgrade already in progress".to_string());
    }
    let result = self.do_upgrade_internal().await;
    self.upgrade_in_progress.store(false, std::sync::atomic::Ordering::Relaxed);
    result
}
```

将之前写的 upgrade 逻辑移到 `do_upgrade_internal()` 中。

upgrade handler：

```rust
pub async fn upgrade_opencode(State(state): State<AppState>) -> Result<impl IntoResponse, AppError> {
    if state.opencode_manager.is_upgrade_in_progress() {
        return Err(AppError::UpgradeInProgress);
    }
    let result = state
        .opencode_manager
        .upgrade()
        .await
        .map_err(|e| AppError::UpgradeFailed(e))?;
    Ok(Json(json!(result)))
}
```

需要在 opencode_manager.rs 顶部添加 `use std::sync::atomic::AtomicBool;`

- [ ] **Step 4: 在 server.rs 中注册新路由**

在 `/api/bridge/opencode/restart` 路由之后添加：

```rust
.route(
    "/api/bridge/opencode/version",
    get(bridge::router::opencode_version),
)
.route(
    "/api/bridge/opencode/upgrade",
    post(bridge::router::upgrade_opencode),
)
```

- [ ] **Step 5: 编译验证**

Run: `cargo build 2>&1` (在 `opencode-bridge` 目录下)
Expected: 编译通过

- [ ] **Step 6: 运行测试**

Run: `cargo test 2>&1` (在 `opencode-bridge` 目录下)
Expected: 所有测试通过

- [ ] **Step 7: Commit**

```
git add opencode-bridge/src/bridge/router.rs opencode-bridge/src/error.rs opencode-bridge/src/server.rs opencode-bridge/src/process/opencode_manager.rs
git commit -m "feat(bridge): add version check and upgrade API endpoints"
```

---

## Task 3: Bridge — 编写单元测试

**Files:**
- Modify: `opencode-bridge/src/process/opencode_manager.rs`（添加 tests 模块）
- Modify: `opencode-bridge/src/bridge/router.rs`（如需要）

- [ ] **Step 1: 为 is_newer_version 写单元测试**

在 `bridge/router.rs` 底部添加 `#[cfg(test)] mod tests`：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_newer_version_major() {
        assert!(is_newer_version("2.0.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_minor() {
        assert!(is_newer_version("1.16.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_patch() {
        assert!(is_newer_version("1.15.1", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_same() {
        assert!(!is_newer_version("1.15.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_older() {
        assert!(!is_newer_version("1.14.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_with_v_prefix() {
        assert!(is_newer_version("v1.16.0", "v1.15.0"));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cargo test -- --test-threads=1 2>&1` (在 `opencode-bridge` 目录下)
Expected: 所有测试通过

- [ ] **Step 3: Commit**

```
git add opencode-bridge/src/bridge/router.rs
git commit -m "test(bridge): add version comparison unit tests"
```

---

## Task 4: Android — 新增 DTO 和 API 方法

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt`
- Modify: `android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

- [ ] **Step 1: 在 BridgeDto.kt 中添加新的 DTO**

在文件末尾（`PairConfirmResponse` 之后）添加：

```kotlin
@Serializable
data class OpencodeVersionResponse(
    val current: String? = null,
    val latest: String? = null,
    @SerialName("hasUpdate")
    val hasUpdate: Boolean = false,
)

@Serializable
data class OpencodeUpgradeResponse(
    val success: Boolean = false,
    @SerialName("previousVersion")
    val previousVersion: String? = null,
    @SerialName("newVersion")
    val newVersion: String? = null,
    val error: String? = null,
    val recovered: Boolean? = null,
    @SerialName("currentVersion")
    val currentVersion: String? = null,
)
```

- [ ] **Step 2: 在 OpencodeApiClient.kt 中添加 API 方法**

在 `bridgePairConfirm()` 方法之后添加：

```kotlin
suspend fun bridgeOpencodeVersion(): OpencodeVersionResponse {
    return get("/api/bridge/opencode/version")
}

suspend fun bridgeOpencodeUpgrade(): OpencodeUpgradeResponse {
    return post("/api/bridge/opencode/upgrade", JsonObject(emptyMap()))
}

suspend fun bridgeOpencodeRestart() {
    postUnit("/api/bridge/opencode/restart", JsonObject(emptyMap()))
}
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: 无编译错误（无 `^e:` 匹配）

- [ ] **Step 4: Commit**

```
git add android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt
git commit -m "feat(android): add opencode version/upgrade DTOs and API methods"
```

---

## Task 5: Android — SettingsViewModel 增加版本/升级状态管理

**Files:**
- Modify: `android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: 添加 OpencodeApiClient 依赖注入和新状态字段**

在 SettingsViewModel 中：

1. 添加构造参数 `private val apiClient: OpencodeApiClient`
2. 添加 `import com.openmate.core.network.OpencodeApiClient`
3. 添加 `import com.openmate.core.network.dto.OpencodeVersionResponse`
4. 添加状态字段：

```kotlin
private val _opencodeVersion = MutableStateFlow<OpencodeVersionResponse?>(null)
val opencodeVersion: StateFlow<OpencodeVersionResponse?> = _opencodeVersion.asStateFlow()

private val _isUpgrading = MutableStateFlow(false)
val isUpgrading: StateFlow<Boolean> = _isUpgrading.asStateFlow()

private val _isRestarting = MutableStateFlow(false)
val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

private val _upgradeError = MutableStateFlow<String?>(null)
val upgradeError: StateFlow<String?> = _upgradeError.asStateFlow()
```

5. 在 `init` 块中调用 `checkVersion()`

- [ ] **Step 2: 添加业务方法**

```kotlin
fun checkVersion() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            _opencodeVersion.value = apiClient.bridgeOpencodeVersion()
            _upgradeError.value = null
        } catch (e: Exception) {
            android.util.Log.e("Settings", "Failed to check version", e)
        }
    }
}

fun upgradeOpencode() {
    viewModelScope.launch(Dispatchers.IO) {
        _isUpgrading.value = true
        _upgradeError.value = null
        try {
            val result = apiClient.bridgeOpencodeUpgrade()
            if (result.success) {
                _opencodeVersion.value = OpencodeVersionResponse(
                    current = result.newVersion,
                    latest = result.newVersion,
                    hasUpdate = false,
                )
            } else {
                val msg = result.error ?: "Upgrade failed"
                if (result.recovered == true) {
                    _opencodeVersion.value = OpencodeVersionResponse(
                        current = result.currentVersion,
                        latest = null,
                        hasUpdate = false,
                    )
                }
                _upgradeError.value = msg
            }
        } catch (e: Exception) {
            _upgradeError.value = e.message ?: "Upgrade failed"
        } finally {
            _isUpgrading.value = false
        }
    }
}

fun restartOpencode() {
    viewModelScope.launch(Dispatchers.IO) {
        _isRestarting.value = true
        try {
            apiClient.bridgeOpencodeRestart()
        } catch (e: Exception) {
            _upgradeError.value = e.message ?: "Restart failed"
        } finally {
            _isRestarting.value = false
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :feature:settings:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: 无编译错误

- [ ] **Step 4: Commit**

```
git add android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt
git commit -m "feat(android): add version check and upgrade state to SettingsViewModel"
```

---

## Task 6: Android — SettingsContent UI 新增 opencode 管理区块

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt`
- Modify: `android/feature/settings/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加 string 资源**

在 `strings.xml` 中添加：

```xml
<string name="opencode_management">OpenCode</string>
<string name="opencode_version_label">Version</string>
<string name="opencode_not_connected">Not connected</string>
<string name="check_for_updates">Check for updates</string>
<string name="upgrade_to">Upgrade to v%1$s</string>
<string name="up_to_date">Up to date</string>
<string name="upgrade_opencode">Upgrade</string>
<string name="restart_opencode">Restart</string>
<string name="upgrade_confirm">Upgrading will restart opencode. Active sessions will be interrupted. Continue?</string>
<string name="restart_confirm">Restart opencode?</string>
<string name="upgrading">Upgrading…</string>
<string name="restarting">Restarting…</string>
<string name="upgrade_success">Upgraded to v%1$s</string>
<string name="upgrade_failed">Upgrade failed</string>
```

- [ ] **Step 2: 在 SettingsContent 的 About 区块之前插入 opencode 管理区块**

在 `SectionHeader(title = stringResource(R.string.about))` 之前插入新的 item：

```kotlin
item {
    val opencodeVersion by viewModel.opencodeVersion.collectAsState()
    val isUpgrading by viewModel.isUpgrading.collectAsState()
    val isRestarting by viewModel.isRestarting.collectAsState()
    val upgradeError by viewModel.upgradeError.collectAsState()

    var showUpgradeDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.upgrade_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showUpgradeDialog = false
                    viewModel.upgradeOpencode()
                }) {
                    Text(stringResource(R.string.upgrade_opencode), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpgradeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.restart_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    viewModel.restartOpencode()
                }) {
                    Text(stringResource(R.string.restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(upgradeError) {
        upgradeError?.let {
            // Error will be shown via snackbar or similar
        }
    }

    SectionHeader(title = stringResource(R.string.opencode_management))
    SettingsCard {
        SettingsRow(
            title = stringResource(R.string.opencode_version_label),
            subtitle = null,
            trailing = {
                Text(
                    text = if (isUpgrading) stringResource(R.string.upgrading)
                           else if (isRestarting) stringResource(R.string.restarting)
                           else opencodeVersion?.current ?: stringResource(R.string.opencode_not_connected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (opencodeVersion?.hasUpdate == true && !isUpgrading && !isRestarting) {
            SettingsRow(
                title = stringResource(R.string.upgrade_to, opencodeVersion?.latest ?: ""),
                subtitle = null,
                showDivider = false,
                modifier = Modifier.clickable { showUpgradeDialog = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.upgrade_opencode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        } else {
            SettingsRow(
                title = stringResource(R.string.restart_opencode),
                subtitle = null,
                showDivider = false,
                modifier = Modifier.clickable(enabled = !isRestarting && !isUpgrading) { showRestartDialog = true },
            )
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: 无编译错误

- [ ] **Step 4: Commit**

```
git add android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt android/feature/settings/src/main/res/values/strings.xml
git commit -m "feat(android): add opencode management section to settings UI"
```

---

## Task 7: Bridge — status API 增加版本字段

**Files:**
- Modify: `opencode-bridge/src/bridge/router.rs`

- [ ] **Step 1: 在 status handler 的返回中增加 opencode 版本**

修改 `status()` 函数，在返回的 JSON 中增加 version 字段：

将 opencode 对象从：
```rust
"opencode": {
    "status": status_str,
    "url": state.config.opencode_url(),
    "directory": state.config.opencode.directory,
}
```
改为：
```rust
"opencode": {
    "status": status_str,
    "version": state.opencode_manager.get_cached_version().await,
    "url": state.config.opencode_url(),
    "directory": state.config.opencode.directory,
}
```

- [ ] **Step 2: 更新 Android 端 BridgeOpencodeInfoDto**

在 `BridgeDto.kt` 的 `BridgeOpencodeInfoDto` 中添加：

```kotlin
data class BridgeOpencodeInfoDto(
    val status: String = "unknown",
    val version: String? = null,
    val url: String = "",
    val directory: String = "",
)
```

- [ ] **Step 3: 编译验证**

Run: `cargo build --manifest-path opencode-bridge/Cargo.toml 2>&1` 和 `.\gradlew.bat :core:network:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: 都通过

- [ ] **Step 4: Commit**

```
git add opencode-bridge/src/bridge/router.rs android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt
git commit -m "feat: include opencode version in bridge status response"
```
