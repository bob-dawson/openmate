# 扫码配对设备去重 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1) Android 扫码配对时传递本地唯一 ID 和设备型号，Bridge 端按 client_device_id 去重（upsert），避免重复配对，显示手机型号名。2) Bridge 数据文件（DB、密钥、配置、日志）统一迁移到 `~/.openmate/` 目录。

**Architecture:** Android 首次启动生成 UUID 作为 installationId，持久化到 DataStore。扫码确认时传 `client_device_id`(本地 ID) + `device_name`(手机型号)。Bridge 端按 `client_device_id` 查找已有设备，存在则更新，不存在则新建。`paired_devices` 表增加 `client_device_id` 列。Bridge 所有数据文件从 `~/.opencode/` 迁移到 `~/.openmate/`（密钥文件 `bridge.key` 已在 `.openmate/`，不需改）。

**Tech Stack:** Rust/SQLite (Bridge), Kotlin/DataStore/Build (Android)

**Breaking Change:** 旧数据库不兼容，客户端需重新配对。

---

## File Structure

### Bridge 端
- Modify: `opencode-bridge/src/bridge_db.rs` — 增加 `client_device_id` 列 + 辅助方法 + 路径迁移 `~/.openmate/`
- Modify: `opencode-bridge/src/auth/scan.rs` — `ScanConfirmRequest` 增加字段 + upsert 逻辑
- Modify: `opencode-bridge/src/log_capture.rs` — 日志路径迁移 `~/.openmate/`
- Modify: `opencode-bridge/src/config.rs` — 配置文件路径迁移 `~/.openmate/`

### Android 端
- Create: `android/core/network/src/main/java/com/openmate/core/network/InstallationIdProvider.kt` — 本地 ID 生成+持久化
- Modify: `android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt` — Request 增加字段
- Modify: `android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt` — 传新字段
- Modify: `android/feature/instance/src/main/java/com/openmate/feature/instance/QrScanViewModel.kt` — 注入 InstallationIdProvider，传本地 ID + 设备名

---

### Task 1: Bridge — 路径迁移 + DB schema (client_device_id 列)

**Files:**
- Modify: `opencode-bridge/src/bridge_db.rs`
- Modify: `opencode-bridge/src/log_capture.rs`
- Modify: `opencode-bridge/src/config.rs`

- [ ] **Step 1: bridge_db.rs — 路径从 `.opencode` 改为 `.openmate`**

`bridge_db.rs:27` 将 `.join(".opencode")` 改为 `.join(".openmate")`。

- [ ] **Step 2: bridge_db.rs — PairedDevice 增加 client_device_id 字段**

```rust
pub struct PairedDevice {
    pub device_id: String,
    pub client_device_id: String,
    pub ip: String,
    pub name: String,
    pub user_agent: String,
    pub paired_at: i64,
    pub last_seen: i64,
}
```

- [ ] **Step 3: bridge_db.rs — migrate 方法更新 CREATE TABLE（不兼容旧 DB）**

```rust
fn migrate(&self) -> Result<(), String> {
    let conn = self.conn()?;
    conn.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA busy_timeout=5000;",
    )
        .map_err(|e| format!("Failed to set pragmas: {}", e))?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS paired_devices (
            device_id TEXT PRIMARY KEY,
            client_device_id TEXT NOT NULL DEFAULT '',
            ip TEXT NOT NULL,
            name TEXT NOT NULL DEFAULT '',
            user_agent TEXT NOT NULL DEFAULT '',
            paired_at INTEGER NOT NULL,
            last_seen INTEGER NOT NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_paired_devices_client_device_id
            ON paired_devices(client_device_id) WHERE client_device_id != '';"
    ).map_err(|e| format!("Migration failed: {}", e))?;
    Ok(())
}
```

- [ ] **Step 4: bridge_db.rs — 更新 insert_device / list_devices 包含 client_device_id**

`insert_device` 改为：

```rust
pub fn insert_device(&self, device: &PairedDevice) -> Result<(), String> {
    let conn = self.conn()?;
    conn.execute(
        "INSERT INTO paired_devices (device_id, client_device_id, ip, name, user_agent, paired_at, last_seen)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            device.device_id,
            device.client_device_id,
            device.ip,
            device.name,
            device.user_agent,
            device.paired_at,
            device.last_seen,
        ],
    ).map_err(|e| format!("Insert failed: {}", e))?;
    Ok(())
}
```

`list_devices` 改为查询 7 列（加上 client_device_id），映射到 PairedDevice。

- [ ] **Step 5: bridge_db.rs — 新增 find_by_client_device_id / update_device 方法**

```rust
pub fn find_by_client_device_id(&self, client_device_id: &str) -> Result<Option<PairedDevice>, String> {
    let conn = self.conn()?;
    let result = conn.query_row(
        "SELECT device_id, client_device_id, ip, name, user_agent, paired_at, last_seen
         FROM paired_devices WHERE client_device_id = ?1",
        params![client_device_id],
        |row| {
            Ok(PairedDevice {
                device_id: row.get(0)?,
                client_device_id: row.get(1)?,
                ip: row.get(2)?,
                name: row.get(3)?,
                user_agent: row.get(4)?,
                paired_at: row.get(5)?,
                last_seen: row.get(6)?,
            })
        },
    );
    match result {
        Ok(dev) => Ok(Some(dev)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(format!("Query failed: {}", e)),
    }
}

pub fn update_device(&self, device: &PairedDevice) -> Result<(), String> {
    let conn = self.conn()?;
    conn.execute(
        "UPDATE paired_devices SET ip = ?1, name = ?2, last_seen = ?3 WHERE device_id = ?4",
        params![device.ip, device.name, device.last_seen, device.device_id],
    ).map_err(|e| format!("Update failed: {}", e))?;
    Ok(())
}
```

- [ ] **Step 6: bridge_db.rs — 更新测试中所有 PairedDevice 构造**

所有测试中构造 `PairedDevice` 的地方加上 `client_device_id` 字段（如 `client_device_id: String::new()`）。

- [ ] **Step 7: log_capture.rs — 日志路径迁移**

`log_capture.rs:132` 将 `.join(".opencode")` 改为 `.join(".openmate")`。

- [ ] **Step 8: config.rs — 配置文件路径迁移**

`config.rs:227` 将 `.join(".opencode")` 改为 `.join(".openmate")`。

- [ ] **Step 9: 运行 Bridge 测试确认通过**

Run: `cargo test --manifest-path D:\openmate\opencode-bridge\Cargo.toml`
Expected: 所有测试通过

- [ ] **Step 10: Commit**

```bash
git add opencode-bridge/src/bridge_db.rs opencode-bridge/src/log_capture.rs opencode-bridge/src/config.rs
git commit -m "feat(bridge): migrate data dir to ~/.openmate, add client_device_id column"
```

---

### Task 2: Bridge — scan_confirm 使用 upsert 逻辑

**Files:**
- Modify: `opencode-bridge/src/auth/scan.rs`

- [ ] **Step 1: 修改 ScanConfirmRequest 增加 client_device_id 字段**

```rust
#[derive(Deserialize)]
pub struct ScanConfirmRequest {
    pub scan_token: String,
    pub device_name: Option<String>,
    pub client_device_id: Option<String>,
}
```

- [ ] **Step 2: 修改 scan_confirm handler 使用 upsert**

将 `scan_confirm` 函数中验证通过后的设备创建逻辑替换为：

```rust
pub async fn scan_confirm(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<ScanConfirmRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let valid = {
        let st = state.scan_token.read().await;
        match st.as_ref() {
            Some(entry) => entry.token == body.scan_token && entry.expires_at > now,
            None => false,
        }
    };

    if !valid {
        return Err(AppError::BadRequest("Invalid or expired scan token".to_string()));
    }

    {
        let mut st = state.scan_token.write().await;
        *st = None;
    }

    let ip = addr.ip().to_string();
    let device_name = body.device_name.unwrap_or_default();

    let (device_id, is_new) = match body.client_device_id {
        Some(cid) if !cid.is_empty() => {
            let existing = state.bridge_db.find_by_client_device_id(&cid)
                .map_err(|e| AppError::DatabaseError(e))?;
            if let Some(mut dev) = existing {
                dev.ip = ip.clone();
                dev.name = device_name;
                dev.last_seen = now;
                state.bridge_db.update_device(&dev)
                    .map_err(|e| AppError::DatabaseError(e))?;
                (dev.device_id, false)
            } else {
                let id_bytes = super::key::generate_random_bytes(8);
                let id = super::key::hex_encode(&id_bytes);
                let device = crate::bridge_db::PairedDevice {
                    device_id: id.clone(),
                    client_device_id: cid,
                    ip: ip.clone(),
                    name: device_name,
                    user_agent: String::new(),
                    paired_at: now,
                    last_seen: now,
                };
                state.bridge_db.insert_device(&device)
                    .map_err(|e| AppError::DatabaseError(e))?;
                (id, true)
            }
        }
        _ => {
            let id_bytes = super::key::generate_random_bytes(8);
            let id = super::key::hex_encode(&id_bytes);
            let device = crate::bridge_db::PairedDevice {
                device_id: id.clone(),
                client_device_id: String::new(),
                ip: ip.clone(),
                name: device_name,
                user_agent: String::new(),
                paired_at: now,
                last_seen: now,
            };
            state.bridge_db.insert_device(&device)
                .map_err(|e| AppError::DatabaseError(e))?;
            (id, true)
        }
    };

    let token = Token::generate(&state.secret_key, &device_id);

    if is_new {
        tracing::info!("Scan pair confirmed for {}, device {} (new)", ip, device_id);
    } else {
        tracing::info!("Scan pair re-confirmed for {}, device {} (existing)", ip, device_id);
    }

    Ok(Json(serde_json::json!({
        "token": token,
        "device_id": device_id,
    })))
}
```

- [ ] **Step 3: 更新 pair.rs 中的 PairedDevice 构造**

`opencode-bridge/src/auth/pair.rs` 中 `pair_confirm` 函数也构造了 `PairedDevice`（`pair.rs:236-243`），需要加 `client_device_id: String::new()` 字段。

- [ ] **Step 4: 运行 Bridge 测试**

Run: `cargo test --manifest-path D:\openmate\opencode-bridge\Cargo.toml`
Expected: 所有测试通过

- [ ] **Step 5: Commit**

```bash
git add opencode-bridge/src/auth/scan.rs opencode-bridge/src/auth/pair.rs
git commit -m "feat(bridge): scan pair with client_device_id upsert"
```

---

### Task 3: Android — InstallationIdProvider

**Files:**
- Create: `android/core/network/src/main/java/com/openmate/core/network/InstallationIdProvider.kt`

- [ ] **Step 1: 创建 InstallationIdProvider**

在 `core/network` 模块下新建文件，用 DataStore 持久化本地安装 ID。首次调用时生成 UUID，之后一直返回同一个值。

```kotlin
package com.openmate.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.installationDataStore: DataStore<Preferences> by preferencesDataStore(name = "installation")

@Singleton
class InstallationIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("installation_id")

    suspend fun getInstallationId(): String {
        val existing = context.installationDataStore.data.map { it[key] }.first()
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        context.installationDataStore.edit { it[key] = newId }
        return newId
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/InstallationIdProvider.kt
git commit -m "feat(android): add InstallationIdProvider for persistent device ID"
```

---

### Task 4: Android — 更新 DTO 和 API Client

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt`
- Modify: `android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`

- [ ] **Step 1: ScanPairConfirmRequest 增加 client_device_id 字段**

```kotlin
@Serializable
data class ScanPairConfirmRequest(
    @SerialName("scan_token") val scanToken: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("client_device_id") val clientDeviceId: String = "",
)
```

- [ ] **Step 2: OpencodeApiClient.bridgeScanPairConfirm 签名增加 clientDeviceId 参数**

```kotlin
suspend fun bridgeScanPairConfirm(scanToken: String, deviceName: String, clientDeviceId: String): ScanPairConfirmResponse = withContext(Dispatchers.IO) {
    val body = ScanPairConfirmRequest(scanToken, deviceName, clientDeviceId)
    val jsonStr = json.encodeToString(ScanPairConfirmRequest.serializer(), body)
    val requestBody = jsonStr.toRequestBody(jsonMediaType)
    val url = buildUrl("/api/bridge/pair/scan-confirm", emptyMap())
    val request = Request.Builder().url(url).post(requestBody).build()
    val response = client.newCall(request).execute()
    val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
    if (!response.isSuccessful) {
        throw ServerUnavailableException("HTTP ${response.code}: $responseBody")
    }
    json.decodeFromString(responseBody)
}
```

- [ ] **Step 3: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt
git commit -m "feat(android): add client_device_id to scan pair request"
```

---

### Task 5: Android — QrScanViewModel 集成 InstallationIdProvider

**Files:**
- Modify: `android/feature/instance/src/main/java/com/openmate/feature/instance/QrScanViewModel.kt`

- [ ] **Step 1: 注入 InstallationIdProvider，传递本地 ID + 设备型号名**

```kotlin
@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
    private val profileRepository: ServerProfileRepository,
    private val installationIdProvider: InstallationIdProvider,
) : ViewModel() {
```

修改 `handleBarcode` 中的 API 调用，将 `parsed.name` 替换为 Android 设备型号名，并传入 `clientDeviceId`：

```kotlin
val clientDeviceId = installationIdProvider.getInstallationId()
val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
val response = try {
    apiClient.bridgeScanPairConfirm(parsed.scanToken, deviceName, clientDeviceId)
} finally {
    apiClient.baseUrl = saved
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :feature:instance:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL，无编译错误

- [ ] **Step 3: Commit**

```bash
git add android/feature/instance/src/main/java/com/openmate/feature/instance/QrScanViewModel.kt
git commit -m "feat(android): pass installation ID + device model in scan pair"
```

---

### Task 6: 端到端验证

- [ ] **Step 1: Bridge 编译测试**

Run: `cargo test --manifest-path D:\openmate\opencode-bridge\Cargo.toml`
Expected: 所有测试通过

- [ ] **Step 2: Bridge 编译 release**

Run: `cargo build --release --manifest-path D:\openmate\opencode-bridge\Cargo.toml`
Expected: 编译成功

- [ ] **Step 3: Android 编译验证**

Run: `.\gradlew.bat assembleDebug --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 手动测试 — 扫码配对**

1. 启动 Bridge（新编译版本）
2. Android 安装新 APK
3. 扫码配对 → 检查 Bridge 日志确认收到 `client_device_id` 和设备型号名
4. 查询 bridge.db 确认 `client_device_id` 已写入
5. 再次扫码（同一台手机）→ 检查 Bridge 日志确认 `re-confirmed (existing)`，DB 中设备记录不增加

- [ ] **Step 5: Commit all (if any fixes needed)**
