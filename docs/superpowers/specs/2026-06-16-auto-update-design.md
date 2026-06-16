# 自动更新机制设计

- **日期**: 2026-06-16
- **状态**: 已确认，待制定实施计划
- **范围**: Bridge（桌面端）+ Android 客户端

## 1. 背景与目标

OpenMate 已改为基于 GitHub Releases 发布：push `v*` tag → GitHub Actions（`.github/workflows/release.yml`）自动构建全平台产物并创建 Release。当前产物：

| 产物 | 平台 |
|------|------|
| `openmate.exe` | Windows |
| `openmate-linux-x86_64` | Linux x86_64（musl 静态） |
| `openmate-linux-arm64` | Linux arm64（musl 静态） |
| `openmate-darwin-arm64` | macOS Apple Silicon |
| `OpenMate-{version}.apk` | Android |
| `relay-gateway-linux-x86_64` | Relay Gateway |

**目标**：添加自动更新机制，让用户便捷升级到新版本，覆盖 Bridge 和 Android 两端。

## 2. 核心决策

1. **Android 设置页是统一入口** —— 类似现有"opencode 升级"卡片，在设置页检查并触发两端更新，而非 Bridge 自行定时检查。
2. **两端都做**（App 自更新 + Bridge 更新），一个完整设计，实施时可分阶段。
3. **Bridge 自替换用 helper-script 方案** —— Bridge 下载新二进制 + 生成平台脚本（Win `.ps1` / Linux·macOS `.sh`）执行 stop→replace→start，复用 `update-bridge.ps1` 成熟逻辑。
4. **Bridge 更新分两阶段**（下载 → 应用）—— 考虑国内 GitHub 网络不稳定，下载与替换物理隔离；下载失败提示用户手动去 GitHub Releases 下载。
5. **不做专门升级成功状态** —— Android 触发 apply 后靠现有重连机制，重连后读 `bridge.version` 对比即可判断成功。

## 3. 整体架构与数据流

### 设置页"检查更新"区

在 Android 设置页（`WorkspaceListScreen.kt` 的 `SettingsContent`）新增卡片组，镜像现有"Opencode Management"卡片样式，含两个子区：

```
┌─ 检查更新 ────────────────────────────┐
│  App 客户端    当前 0.1.19  最新 0.1.20 │
│  [检查更新]  [下载并安装 ▶]             │
│                                         │
│  Bridge       当前 0.1.19  最新 0.1.20 │
│  [检查更新]  [立即升级 ▶]              │
└─────────────────────────────────────────┘
```

进入设置页时自动检查一次（镜像现有 `init { checkVersion() }`），另各有手动"检查更新"按钮。

### App 自身更新流

```
Android: 读本地 versionName ──┐
                                ├─ 对比 → hasAppUpdate
GitHubReleaseClient: 查 latest ┘
   ↓ 用户点"下载并安装"
Android: downloadClient 流式下载 OpenMate-{ver}.apk → cacheDir/file_cache/
   → FileOpener.installApk()（权限/FileProvider/未知来源引导已就绪）→ 系统安装器接管
```

### Bridge 更新流

```
Android: 读 /api/bridge/status 的 bridge.version ──┐
                                                    ├─ 对比 → hasBridgeUpdate
GitHubReleaseClient: 查 latest ────────────────────┘
   ↓ 用户点"立即升级"
Android: POST /api/bridge/upgrade/download → 轮询 GET /upgrade/status（进度条）
   ├─ downloaded → 自动 POST /upgrade/apply
   │     ↓ Bridge: 生成 helper-script → spawn → 优雅退出 → 脚本替换+启动
   │     ↓ Android 断线 → ConnectionManager 指数退避重连 → 版本对比 → 成功
   └─ failed → 提示 GitHub Releases 链接（手动用 update-bridge.ps1）
```

## 4. Bridge 端设计

### 4.1 新增 API（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/bridge/upgrade/download` | 阶段1：异步开始下载新二进制，立即返回 202 |
| GET | `/api/bridge/upgrade/status` | 查下载状态：`{state, progress, version, error}` |
| POST | `/api/bridge/upgrade/apply` | 阶段2：下载完成后执行 helper-script 自替换+重启 |

`state`: `idle` / `downloading` / `downloaded` / `failed`

权限：与其他 `/api/bridge/*` 一致（需 bearer token）。无单独 upgrade-success API——靠 Android 重连后版本对比。

### 4.2 UpgradeManager 模块（新增 `src/update/`）

镜像现有 `OpencodeManager` 升级模式（`AtomicBool` 防重入 + spawn 异步任务）：

```
src/update/
├── mod.rs        # upgrade() 入口 + AtomicBool 防重入（覆盖 download→apply）
├── manager.rs    # do_download(): 查 GitHub latest → 选平台 → 流式下载
│                 # do_apply(): 生成脚本 → spawn → 触发 shutdown
├── platform.rs   # 平台→产物名映射 + 当前 exe 路径探测
└── script.rs     # 跨平台 helper-script 生成
```

状态用 `Arc<Mutex<UpgradeState>>`（或 atomic）持有，供 status API 读取。

### 4.3 平台→产物映射（基于 `std::env::consts::{OS, ARCH}`）

| 运行平台 | 下载产物 |
|---------|---------|
| Windows x86_64 | `openmate.exe` |
| Linux x86_64 | `openmate-linux-x86_64` |
| Linux aarch64 | `openmate-linux-arm64` |
| macOS aarch64 | `openmate-darwin-arm64` |

### 4.4 两阶段实现

**download 阶段**（Bridge 全程正常运行，失败不影响）：
1. 查 `api.github.com/repos/bob-dawson/openmate/releases/latest`（复用 `registry_fetch_version` 的 reqwest 模板），取 tag_name + 目标 asset 的 `browser_download_url`
2. reqwest 流式下载到 `{tempdir}/openmate.update`，5 min 超时，更新 progress
3. 成功 → `state=downloaded`；失败 → `state=failed` + error

**apply 阶段**（仅 downloaded 后可触发）：
1. 生成 helper-script（见 4.5），写入 `{tempdir}/openmate-update.{ps1|sh}`
2. `Command::spawn()` detached 启动（Win: `CREATE_NO_WINDOW`；Linux/macOS: `setsid`）
3. spawn 成功 → `state.shutdown_tx.send(true)` 走现有优雅退出 → `process::exit(0)`
4. 脚本接管：轮询旧 PID 退出（≤30s）→ 备份旧二进制 `.bak` → 覆盖 → 启动新进程 → 脚本自删

### 4.5 helper-script（关键逻辑）

**Windows `.ps1`**：
```powershell
$pid=<OLD>; $new="<temp>\openmate.update"; $tgt="<exe>"
$deadline=(Get-Date).AddSeconds(30)
while((Get-Date) -lt $deadline -and (Get-Process -Id $pid -EA SilentlyContinue)){sleep -ms 500}
Copy-Item $tgt "$tgt.bak" -Force; Move-Item $new $tgt -Force
Start-Process $tgt -WindowStyle Hidden; Remove-Item $MyInvocation.MyCommand.Path -Force
```

**Linux/macOS `.sh`**：
```bash
OLDPID=<OLD>; NEW=/tmp/openmate.update; TGT=<exe>
for i in $(seq 1 60); do kill -0 $OLDPID 2>/dev/null || break; sleep 0.5; done
cp "$TGT" "$TGT.bak"; mv "$NEW" "$TGT"; chmod +x "$TGT"
nohup "$TGT" >/dev/null 2>&1 &; rm -- "$0"
```

### 4.6 回滚

脚本内替换失败时（Bridge 已退出），从 `.bak` 恢复旧版本并启动，保证 Bridge 总能起来（最坏退回旧版本，不变砖）。

### 4.7 服务模式限制

apply 针对普通进程模式。检测到服务模式（Windows Service / systemd）时 apply 返回错误："服务模式请手动更新"——服务进程生命周期由服务管理器控制，helper-script 直接启动会冲突。服务模式自动 apply 留作后续。

## 5. Android 端设计

### 5.1 GitHubReleaseClient（新增 `core/network`）

独立 OkHttpClient（不走 `GatewayInterceptor`/`BearerTokenInterceptor`，因访问 `api.github.com` 而非 bridge），参考现有 `TempHttpClient`：

```kotlin
class GitHubReleaseClient(@Named("github") client: OkHttpClient) {
    suspend fun fetchLatestRelease(): LatestReleaseDto  // GET api.github.com/repos/bob-dawson/openmate/releases/latest
}
data class LatestReleaseDto(tagName, version /*去 v*/, apkAssetUrl, releaseUrl /*html_url 失败提示用*/)
```

DI：`NetworkModule` 新增 `@Named("github")` OkHttpClient（10s/30s 超时，无拦截器）。

### 5.2 OpencodeApiClient 新增方法（走现有 bridge 拦截器）

```kotlin
suspend fun bridgeUpgradeDownload()                       // POST /api/bridge/upgrade/download
suspend fun bridgeUpgradeStatus(): BridgeUpgradeStatusDto // GET /api/bridge/upgrade/status
suspend fun bridgeUpgradeApply()                          // POST /api/bridge/upgrade/apply
```

新增 DTO：`BridgeUpgradeStatusDto(state, progress, version, error)`。

### 5.3 SettingsViewModel 扩展

新增 StateFlow：
- `appUpdateInfo` / `bridgeUpdateInfo`（currentVersion, latestVersion, hasUpdate）
- `isCheckingUpdates`
- `bridgeDownloadState`（idle / downloading(progress) / downloaded / failed(error)）
- `appDownloadState`（idle / downloading(progress) / done / failed）

新增方法：
- `checkUpdates()`：并行查 app（本地 `versionName` vs GitHub）+ bridge（`bridgeStatus().bridge.version` vs GitHub）；`init` 块调用一次
- `downloadAndInstallApp()`：下载 APK → `installApk()`
- `upgradeBridge()`：download → 轮询 status（镜像 opencode 升级 40×3s）→ downloaded 自动 apply

### 5.4 设置页 UI

新增"检查更新"卡片组，复用现有 `SectionHeader` + `SettingsCard` + `SettingsRow` 样式，镜像"Opencode Management"卡片。两个子区（App / Bridge），各有版本显示 + 检查按钮 + 升级按钮（hasUpdate 时）+ 进度/失败提示。

### 5.5 App 更新流

`downloadClient` 流式下载 APK → `cacheDir/file_cache/OpenMate-{ver}.apk`（FileProvider 已暴露 `file_cache/`，无需改 `file_paths.xml`）→ `FileOpener.installApk()`。下载失败 → 提示"前往 GitHub Releases 手动下载 APK"。

### 5.6 Bridge 更新流

POST download → 轮询 status（进度条）→ downloaded 自动 POST apply → Bridge 重启 → 现有 `ConnectionManager` 指数退避重连 → 重连后 `checkUpdates()` 刷新 → 版本变新即成功；下载 failed → 提示 GitHub Releases 链接（手动用 `update-bridge.ps1`）。

## 6. 边界与错误处理

| 场景 | 处理 |
|------|------|
| GitHub API 限流（未认证 60/hr） | app + bridge 共用一次 `fetchLatestRelease`（同一 release 含两端产物），每次检查只耗 1 配额，单用户够用。超限 → "检查失败，稍后重试" |
| 版本号 v 前缀 | tag `v0.1.20` vs `CARGO_PKG_VERSION` `0.1.20`，对比前 `trim_start_matches('v')`。Bridge 复用 `is_newer_version`；Android 新写简单 semver 比较 |
| Bridge 下载中断/超时 | reqwest 流式 5 min 超时；中断 → `state=failed` + error；Android 轮询见 failed → 提示手动 |
| App 下载中断 | `downloadClient` 流式；中断 → 提示重试/手动 |
| helper-script 替换失败（Bridge 已退出） | 脚本从 `.bak` 恢复旧版本并启动；Android 重连后发现版本未变 → "升级失败，已恢复" |
| apply 后长时间重连失败 | 走现有 `ConnectionManager` 连接失败错误处理 |
| 并发防护 | Bridge：`AtomicBool` 覆盖 download→apply；Android：升级中按钮禁用 |
| 重复 download 请求 | `state=downloaded` 直接返回已就绪；`state=downloading` 返回进行中，不重复下载 |
| 服务模式 | apply 返回错误提示手动（见 4.7） |

## 7. 测试策略

**Bridge（`src/update/`）**：
- `platform.rs`：平台→产物映射（断言 `OS/ARCH` 组合选对 asset）
- `script.rs`：helper-script 生成——断言含关键步骤（等 PID 退出、`.bak` 备份、覆盖、启动、自删），Win ps1 / Linux·macOS sh 各一组
- `manager.rs`：mockito mock GitHub API，测 download 成功/失败/超时、apply 触发 spawn+shutdown
- 集成测试（`tests/`）：upgrade 三 API，`tower::ServiceExt` + mock GitHub，覆盖 idle→downloading→downloaded→apply 流转

**Android**：
- `GitHubReleaseClient`：MockWebServer 测 JSON 解析（tag_name、assets、version 去 v、apkAssetUrl）
- 版本比较：纯单元测试
- `SettingsViewModel`：fake repository 测 `checkUpdates`、`upgradeBridge` 状态流转、失败降级提示

## 8. 不做的（YAGNI）

- 不显示 changelog/release notes（只版本号）
- 不做后台定时检查（仅设置页打开时 + 手动按钮，与 opencode 检查一致）
- 不加 GitHub 镜像/代理配置（直连，失败提示手动）
- 服务模式自动 apply（暂不支持，提示手动）
- 专门升级成功状态 API（靠重连+版本对比）

## 9. 实施阶段建议

设计覆盖两端，实施可拆为两个独立阶段（共享 Android 设置页 UI 框架与 GitHubReleaseClient）：

- **阶段 1：App 自更新**（最独立、最快见效）—— GitHubReleaseClient + App 下载安装流 + 设置页 App 卡片
- **阶段 2：Bridge 更新**（技术难点）—— Bridge `src/update/` 模块 + 三 API + helper-script + Android Bridge 卡片 + 重连版本对比

## 10. 可复用基础设施

### Bridge 侧
| 能力 | 位置 | 复用 |
|------|------|------|
| 版本编译期注入 | `bridge/router.rs:36` | `env!("CARGO_PKG_VERSION")` |
| Semver 比较 | `bridge/router.rs:150-160` | `is_newer_version` |
| 外网 HTTP GET + JSON | `process/opencode_manager.rs:395-418` | `registry_fetch_version` 模板 |
| 防重入异步任务 | `process/opencode_manager.rs:420-431` | `AtomicBool::swap` |
| 优雅停止 | `api/shutdown.rs` + `server.rs:281-292` | apply 后触发 |
| KV 配置存储 | `bridge_db.rs:243-327` | 新增配置无需迁移 |
| 现有 update-bridge.ps1 | `scripts/update-bridge.ps1` | helper-script 逻辑来源 |

### Android 侧
| 能力 | 位置 | 复用 |
|------|------|------|
| REQUEST_INSTALL_PACKAGES | `AndroidManifest.xml:7` | 已声明 |
| FileProvider + file_cache | `AndroidManifest.xml` + `file_paths.xml` | APK 落 cacheDir/file_cache |
| APK 安装 | `core/common/.../FileOpener.kt:58-80` | `installApk()` 含未知来源引导 |
| 大文件下载 OkHttpClient | `NetworkModule.kt:60-72` | `@Named("download")` 60s connect |
| 不走拦截器 HTTPS 参考 | `TempHttpClient.kt` | GitHubReleaseClient 参考 |
| 设置 ViewModel 框架 | `feature/settings/.../SettingsViewModel.kt` | checkVersion/upgradeOpencode 模式 |
| 设置 UI 卡片样式 | `WorkspaceListScreen.kt:491-782` | SectionHeader/SettingsCard/SettingsRow |
| app 版本读取 | `WorkspaceListScreen.kt:756` | getPackageInfo().versionName |
