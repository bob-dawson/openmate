# opencode 重启与升级功能设计

**日期**: 2026-05-15
**状态**: 已确认

## 背景

配置更新（技能、模型）或 opencode 升级后需要重启 opencode 才能生效。目前在 Android 端没有操作入口，需要用户手动到 PC 上操作。

Bridge 已有 opencode 进程的 start/stop/restart API，但没有升级和版本检查能力。

## 目标

- Android 设置页提供"重启 opencode"和"升级 opencode"操作入口
- Bridge 新增版本检查和升级 API
- 升级失败时自动恢复旧版本，保证可用性

## 范围

- opencode 进程的重启与升级
- Bridge 提供 API，Android 提供操作 UI
- **不在范围内**：Bridge 自身的升级

## 技术背景

- opencode 通过 npm 全局安装（`npm install -g opencode-ai`），当前版本 1.15.0
- Bridge 已有进程管理：start/stop/restart，通过 `cmd /C` 执行命令
- opencode 的 `/global/health` 返回 `{ healthy: true, version: "1.15.0" }`
- Bridge 当前 `check_health()` 只检查 HTTP 状态码，未解析 version 字段

---

## Bridge API 设计

### 新增接口

#### 1. GET /api/bridge/opencode/version

获取 opencode 当前版本和最新可用版本。

**实现**：
- `current`：从 `OpencodeManager` 中缓存的 version 字段读取（来源于 `/global/health` 响应）
- `latest`：执行 `npm view opencode-ai version`（超时 30s），解析 stdout 获取
- `hasUpdate`：semver 比较 current < latest

**返回体**：
```json
{
  "current": "1.15.0",
  "latest": "1.16.0",
  "hasUpdate": true
}
```

**错误情况**：
- opencode 未运行且无缓存 version → `current` 为 null
- npm view 失败/超时 → `latest` 为 null，`hasUpdate` 为 false

#### 2. POST /api/bridge/opencode/upgrade

执行 opencode 升级。

**流程**：
```
1. 记录当前 version（previousVersion）
2. stop opencode（复用已有逻辑）
3. 执行 npm install -g opencode-ai@latest（超时 300s，捕获 stdout/stderr）
4. start opencode（复用已有逻辑）
5. 调 /global/health 验证并获取新 version
6. 返回结果
```

**失败恢复**：步骤 3 失败后仍执行步骤 4 尝试恢复旧版本。

**返回体**：

成功：
```json
{
  "success": true,
  "previousVersion": "1.15.0",
  "newVersion": "1.16.0"
}
```

失败（npm 失败但恢复成功）：
```json
{
  "success": false,
  "error": "npm install failed: ...",
  "recovered": true,
  "currentVersion": "1.15.0"
}
```

失败（npm 失败且恢复也失败）：
```json
{
  "success": false,
  "error": "npm install failed: ...",
  "recovered": false
}
```

### 已有接口（复用）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/bridge/opencode/restart` | 重启 opencode 进程（已有） |
| `POST` | `/api/bridge/opencode/stop` | 停止 opencode（已有） |
| `POST` | `/api/bridge/opencode/start` | 启动 opencode（已有） |

### 认证

所有新接口需 Bearer token（走已有 auth 中间件），不在公开路径列表中。

---

## Bridge 内部改造

### 1. OpencodeManager 增加 version 缓存

```rust
pub struct OpencodeManager {
    // 现有字段...
    opencode_version: Arc<RwLock<Option<String>>>,  // 新增
}
```

### 2. 改造 check_health()

现有实现只检查 HTTP 状态码。改为：
- 发送 GET `/global/health`
- 解析 response body 的 `version` 字段
- 缓存到 `opencode_version`
- 返回值保持 `bool`（向后兼容）

### 3. 新增方法

- `get_cached_version() -> Option<String>`：读缓存版本
- `get_latest_version() -> Result<String, String>`：执行 `npm view opencode-ai version`
- `upgrade() -> UpgradeResult`：完整升级流程（stop → npm install → start → verify）

---

## Android 设置页 UI 设计

### 位置

在现有 `SettingsContent`（WorkspaceListScreen.kt tab index 2）的 **About（关于）** 分区下，新增 **opencode 管理** 区块。

### UI 元素

| 元素 | 类型 | 说明 |
|------|------|------|
| 当前版本 | 文本 | 如 "opencode v1.15.0" |
| 检查更新 | 按钮 | 无更新时灰显/隐藏，有更新时显示 "升级到 v1.16.0" |
| 重启 opencode | 按钮 | 始终显示 |

### 交互流程

**进入设置页：**
1. 自动调 `GET /api/bridge/opencode/version`
2. 显示当前版本号
3. `hasUpdate = true` 时显示升级按钮（带版本号），否则隐藏

**点击升级：**
1. 确认弹窗："升级将重启 opencode，进行中的会话会被中断。继续？"
2. 确认后调 `POST /api/bridge/opencode/upgrade`
3. 显示 loading 指示器（按钮不可重复点击）
4. 成功：Toast "已升级到 v{new}" + 刷新版本号
5. 失败：显示错误信息（含 recovered 状态）

**点击重启：**
1. 确认弹窗："确定重启 opencode？"
2. 确认后调 `POST /api/bridge/opencode/restart`
3. 显示 loading
4. 成功：Toast "重启完成"
5. 失败：显示错误信息

### 数据层

**OpencodeApiClient 新增方法：**
- `getOpencodeVersion(): VersionInfo` → `GET /api/bridge/opencode/version`
- `upgradeOpencode(): UpgradeResult` → `POST /api/bridge/opencode/upgrade`

**SettingsViewModel 新增状态：**
- `opencodeVersion: StateFlow<VersionInfo?>`
- `isUpgrading: StateFlow<Boolean>`
- `isRestarting: StateFlow<Boolean>`
- `checkVersion()`：调 version API 更新状态
- `upgradeOpencode()`：调 upgrade API
- `restartOpencode()`：调 restart API

---

## 错误处理

| 场景 | Bridge 行为 | Android 展示 |
|------|------------|-------------|
| npm view 超时 | latest 返回 null | 不显示升级按钮 |
| npm install 超时(300s) | 终止进程，尝试恢复 | Toast "升级超时" |
| npm install 失败 | 尝试 start 恢复 | Toast 具体错误信息 |
| 升级后 start 失败 | recovered=false | Toast "升级失败且无法恢复，请手动检查" |
| 升级后 health 检查失败 | 等待重试（同 start 逻辑） | 同上 |
| opencode 未运行 | current 为 null | 显示 "未连接" |

## 不做的事

- Bridge 自身升级（手动部署）
- 自动定时检查更新（仅进入设置页时检查）
- 降级支持
