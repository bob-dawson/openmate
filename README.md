# OpenMate

opencode 的原生 Android 客户端，通过 LAN/Tailscale 连接运行在 PC 上的 opencode 实例，在手机上管理工作区、浏览会话、发送消息、响应权限请求与提问。

## 架构

```
┌──────────────┐          LAN / Tailscale          ┌─────────────────────┐
│  Android App │  ──── HTTP + SSE ────  :4097 ────▶│  OpenMate Bridge    │
│  (OpenMate)  │          Bearer Token              │  (Rust, port 4097)  │
│              │                                  │    ├─ auth (HMAC)    │
│              │                                  │    ├─ proxy ────────▶│ opencode serve
│              │                                  │    ├─ process mgr    │  (port 4098)
│              │                                  │    ├─ fs API         │
│              │                                  │    └─ SSE proxy      │
└──────────────┘                                  └─────────────────────┘
```

**Bridge-only 模式**：Android 客户端只连接 Bridge（端口 4097），不直连 opencode。Bridge 作为反向代理 + 进程管理器 + 文件服务器 + 认证网关，转发所有 REST/SSE 请求到 opencode（端口 4098），并提供扩展文件系统 API。连接时验证 Bridge 状态，非 Bridge 端点返回 `NOT_BRIDGE`。

## 快速开始

### 1. 安装 Bridge 网关

**Windows：**

```powershell
cd opencode-bridge
cargo build --release

# 前台运行
.\target\release\openmate.exe

# 或安装为系统服务（开机自启）
.\target\release\openmate.exe install
# 卸载服务：
# .\target\release\openmate.exe uninstall
```

**Linux：**

```bash
cd opencode-bridge
cargo build --release

# 前台运行
./target/release/openmate

# 或安装为 systemd 服务（开机自启）
sudo ./target/release/openmate install
# 卸载服务：
# sudo ./target/release/openmate uninstall
```

Bridge 启动后会自动拉起 `opencode serve`（默认端口 4098）。确保 `opencode` 可执行文件在 PATH 中。

**配置文件**（可选）：在与 `openmate` 可执行文件相同目录下创建 `bridge.toml`：

```toml
[bridge]
port = 4097          # Bridge 监听端口
hostname = "0.0.0.0" # 监听地址
auth_enabled = true  # 认证开关（默认开启）

[opencode]
binary = "opencode"    # opencode 可执行文件名或路径
hostname = "127.0.0.1"
port = 4098
auto_start = true      # Bridge 启动时自动拉起 opencode
auto_restart = true    # opencode 崩溃后自动重启

[fs]
allowed_paths = []     # 空 = 允许所有路径
```

### 2. 安装 Android 客户端

```bash
cd android
.\gradlew.bat assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

或用 Android Studio 打开 `android/` 目录直接编译运行。

### 3. 配对与连接

首次连接 Bridge 需要完成 PIN 配对认证：

1. **Android 端**：打开 App → 添加实例 → 输入 Bridge 地址（如 `100.71.116.3`）和端口（`4097`）→ 点击 Save
2. **Android 端**：App 自动发起配对请求，显示 6 位 PIN 码
3. **PC 端**：在 Bridge 所在电脑上批准 PIN：

```powershell
openmate.exe approve 123456
```

4. **Android 端**：点击 Confirm → 配对完成，自动连接

配对成功后 token 会保存在设备上，后续连接无需重复配对。如果 token 失效（如 Bridge 重置密钥），App 会提示重新配对。

重置 Bridge 密钥（使所有已配对设备的 token 失效）：

```powershell
openmate.exe reset-token
```

## 功能

### Android 客户端

| 功能 | 说明 |
|------|------|
| 实例管理 | 添加/编辑/删除 Bridge 实例，测试连接 |
| PIN 配对 | 首次连接通过 PIN 码配对认证 |
| 工作区列表 | 按目录分组展示所有会话，支持搜索 |
| 会话列表 | 独立会话浏览，支持创建/删除/重命名 |
| 聊天详情 | 发送消息、流式接收回复、12 种 Part 类型渲染 |
| 权限响应 | 允许/始终允许/拒绝权限请求 |
| 提问响应 | 选择答案提交或拒绝 |
| TODO 列表 | 会话任务进度（进行中/待办/已完成） |
| 模型选择 | 选择 AI 模型与 provider |
| Skill 选择 | 选择可用 skill |
| 文件浏览器 | 浏览工作区目录、查看/下载文件 |
| Markdown 渲染 | compose-markdown 库渲染代码块 |
| 二进制文件下载 | 下载→缓存→系统 Intent 打开 |
| 消息附件 | 从文件/相册选择附件发送 |
| 会话操作 | 中止、压缩/摘要、fork |
| SSE 实时推送 | 会话/消息/权限/提问/TODO 事件实时更新 |
| 设置页 | 通知开关、自动允许规则、缓存管理 |

### Bridge Agent

| 功能 | 说明 |
|------|------|
| 反向代理 | 所有未匹配路由转发到 opencode，剥离 Authorization 头 |
| 认证 | HMAC-SHA256 token + PIN 配对，IP 绑定，速率限制 |
| 进程管理 | 自动启动/停止/重启 opencode，崩溃检测 + 自动恢复 |
| 系统服务 | Windows (Win32 Service) / Linux (systemd)，开机自启 |
| SSE 代理 | mpsc channel + ReceiverStream，自动重连 |
| 文件系统 API | 目录列表、文件读写、创建目录、搜索、上传下载 |
| 静态文件服务 | `/files/{*path}` 路径，MIME 探测 |
| 路径白名单 | PathGuard 校验所有 FS 操作（`allowed_paths=[]` = 允许全部） |

## Bridge CLI 命令

| 命令 | 说明 |
|------|------|
| `openmate` | 前台运行 |
| `openmate -c config.toml` | 指定配置文件 |
| `openmate install` | 安装为系统服务并启动 |
| `openmate uninstall` | 卸载系统服务 |
| `openmate service` | 以服务模式运行（由系统调用） |
| `openmate approve <pin>` | 批准配对 PIN |
| `openmate reset-token` | 重置密钥，使所有 token 失效 |

## Bridge API

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/bridge/status` | 公开 | Bridge 版本 + opencode 状态 + auth_enabled |
| POST | `/api/bridge/pair/request` | 公开 | 请求配对 PIN |
| POST | `/api/bridge/pair/approve` | 仅 localhost | 批准 PIN |
| POST | `/api/bridge/pair/confirm` | 公开 | 确认配对，获取 token |
| POST | `/api/bridge/opencode/start` | Bearer | 启动 opencode |
| POST | `/api/bridge/opencode/stop` | Bearer | 停止 opencode |
| POST | `/api/bridge/opencode/restart` | Bearer | 重启 opencode |
| GET | `/api/bridge/fs/roots` | Bearer | 文件系统根目录列表 |
| GET | `/api/bridge/fs/list?path=` | Bearer | 目录列表 |
| GET | `/api/bridge/fs/stat?path=` | Bearer | 文件/目录元数据 |
| GET | `/api/bridge/fs/read?path=` | Bearer | 读取文件（文本/二进制 base64） |
| GET | `/api/bridge/fs/download?path=` | Bearer | 流式下载文件 |
| PUT | `/api/bridge/fs/upload?path=` | Bearer | 上传文件（max 100MB） |
| POST | `/api/bridge/fs/write` | Bearer | 写入文件 |
| POST | `/api/bridge/fs/mkdir` | Bearer | 创建目录 |
| POST | `/api/bridge/fs/search` | Bearer | 搜索文件 |
| GET | `/files/{*path}` | Bearer | 静态文件服务 |
| GET | `/api/opencode/global/event` | Bearer | SSE 代理 |
| ANY | 其他 | Bearer | 转发到 opencode |

### 认证流程

```
Android                      Bridge (port 4097)                    PC
  │                              │                                  │
  │  POST /pair/request ────────▶│  生成 PIN，绑定 IP               │
  │  ◀─────── { pin: "123456" } │                                  │
  │                              │                                  │
  │   显示 PIN "123456"          │  openmate approve 123456 ◀───────│
  │                              │  标记 PIN 已批准                  │
  │                              │                                  │
  │  POST /pair/confirm ────────▶│  验证 PIN + IP，生成 HMAC token  │
  │  ◀─────── { token: "..." }  │                                  │
  │                              │                                  │
  │  后续请求携带                │                                  │
  │  Authorization: Bearer ... ─▶│  验证 token → 放行 → 转发 opencode
```

## Android 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.0 |
| UI | Jetpack Compose + Material 3 (暗色主题) |
| 架构 | MVVM + Hilt DI + Room + OkHttp |
| 构建 | AGP 8.11.0 / KSP 2.2.0-2.0.2 / Compose BOM 2025.07.00 |
| SDK | minSdk 26 / targetSdk 36 |
| 导航 | Navigation Compose (单 Activity) |
| 网络 | OkHttp (REST + SSE 长连接) + Bearer Token 认证 |
| 数据库 | Room v9 (每个实例独立 SQLite) |
| Token 存储 | DataStore<Preferences> |
| Markdown | compose-markdown |
| i18n | 标准 Android string resources (英文默认 + 中文) |

### 模块结构

```
android/
  app/                → OpenMateApp, MainActivity, NavHost, ConnectionManager
  core/
    common/           → Result<T>, Flow extensions, AppDispatchers
    domain/           → 12 domain models (Part 12 子类型), 7 repository 接口
    data/             → 7 repository 实现 + EventDispatcher + SSE event handlers
    database/         → Room v9, 6 entities, 6 DAOs, ActiveDatabaseProvider
    network/          → OpencodeApiClient, SseClient, TokenStore, BearerTokenInterceptor
    ui/               → 暗色主题 (opencode palette), MessageBubble, StreamingText
  feature/
    instance/         → 实例列表/添加/配对 PIN 对话框
    session/          → 工作区/会话/聊天/文件浏览器
    settings/         → 设置页
```

### 数据流

```
REST: OpencodeApiClient (+ Bearer token) → DTOs → toDomain() → Domain → toEntity() → Room → Flow → ViewModel → UI
SSE:  SseClient (+ Bearer token) → SseParser → SseData → EventDispatcher → EventHandler → Room → Flow → UI
```

## Bridge 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Rust (edition 2024) |
| Web | Axum 0.8 + Tower HTTP 0.6 (CORS + tracing) |
| HTTP | Reqwest 0.12 (stream + json) |
| 异步 | Tokio 1 (full), tokio-stream, tokio-util |
| 认证 | HMAC-SHA256 (hmac + sha2) + 随机密钥 (getrandom) |
| 服务 | Windows: windows-service crate / Linux: systemd |
| 配置 | TOML (bridge.toml) |
| CLI | Clap 4 (derive) |

### Bridge 源码结构

```
opencode-bridge/src/
  main.rs              # CLI 入口
  server.rs            # axum server 启动 + graceful shutdown
  service_windows.rs   # Windows 服务 (install/uninstall/run)
  service_linux.rs     # Linux systemd 服务 (install/uninstall)
  auth/
    key.rs             # SecretKey 生成/加载/持久化
    token.rs           # HMAC-SHA256 token 生成/验证
    pair.rs            # PIN 配对 (request/approve/confirm)
    middleware.rs      # 认证中间件 (public/localhost/bearer)
  bridge/router.rs     # Bridge API handlers
  process/             # opencode 进程管理
  proxy/               # REST + SSE 代理
  fs/                  # 文件系统 API
  files/               # 静态文件服务
```

## 构建 & 部署

### 开发构建

```bash
# Bridge
cd opencode-bridge
cargo build --release
# 产出 target/release/openmate.exe

# Android
cd android
.\gradlew.bat assembleDebug --no-daemon
# 产出 app/build/outputs/apk/debug/app-debug.apk
```

### 一键部署

```powershell
# 停止 Bridge → 复制二进制 → 重启 → 安装 APK 到设备
scripts\deploy.bat
```

## 项目结构

```
openmate/
  android/               → Android 客户端 (Kotlin)
  opencode-bridge/       → Bridge Agent (Rust)
  scripts/               → 调试/部署脚本
    deploy.bat           → 部署脚本
    session_tool.py      → opencode REST API 查询工具
    pull_android_db.py   → 从设备拉取 Room DB
    analyze_android_db.py → 分析 Room DB
  docs/                  → 设计文档
  AGENTS.md              → Workspace 级指引
  android/AGENTS.md      → Android 架构/约定/API 参考
  opencode-bridge/AGENTS.md → Bridge 架构/约定
```

## License

Private project — not publicly distributed.
