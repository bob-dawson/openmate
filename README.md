# OpenMate

opencode 的原生 Android 客户端，通过 LAN/Tailscale 连接运行在 PC 上的 opencode 实例，在手机上管理工作区、浏览会话、发送消息、响应权限请求与提问。

## 架构

```
┌──────────────┐          LAN / Tailscale          ┌─────────────────────┐
│  Android App │  ──── HTTP + SSE ────  :4097 ────▶│  opencode-bridge    │
│  (OpenMate)  │                                  │  (Rust, port 4097)  │
│              │                                  │    ├─ proxy ────────▶│ opencode serve
│              │                                  │    ├─ process mgr    │  (port 4096)
│              │                                  │    ├─ fs API         │
│              │                                  │    └─ SSE proxy      │
└──────────────┘                                  └─────────────────────┘
```

**Bridge-only 模式**：Android 客户端只连接 Bridge（端口 4097），不直连 opencode。Bridge 作为反向代理 + 进程管理器 + 文件服务器，转发所有 REST/SSE 请求到 opencode（端口 4096），并提供扩展文件系统 API。连接时验证 Bridge 状态，非 Bridge 端点返回 `NOT_BRIDGE`。

## 功能

### Android 客户端

| 功能 | 说明 |
|------|------|
| 实例管理 | 添加/编辑/删除 Bridge 实例，测试连接 |
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
| 消息附件 | 发送消息时附加文件 |
| 会话操作 | 中止、压缩/摘要、fork |
| SSE 实时推送 | 会话/消息/权限/提问/TODO 事件实时更新 |
| 设置页 | 通知开关、自动允许规则、缓存管理 |

### Bridge Agent

| 功能 | 说明 |
|------|------|
| 反向代理 | 所有未匹配路由转发到 opencode |
| 进程管理 | 自动启动/停止/重启 opencode，崩溃检测 + 自动恢复 |
| SSE 代理 | mpsc channel + ReceiverStream，自动重连 |
| 文件系统 API | 目录列表、文件读写、创建目录、搜索文件 |
| 文件下载 | 流式下载，content-length + content-disposition |
| 静态文件服务 | `/files/{*path}` 路径，MIME 探测 |
| 路径白名单 | PathGuard 校验所有 FS 操作（`allowed_paths=[]` = 允许全部） |

## Bridge API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/bridge/status` | Bridge 版本 + opencode 状态 |
| POST | `/api/bridge/opencode/start` | 启动 opencode |
| POST | `/api/bridge/opencode/stop` | 停止 opencode |
| POST | `/api/bridge/opencode/restart` | 重启 opencode |
| GET | `/api/bridge/fs/roots` | 文件系统根目录列表 |
| GET | `/api/bridge/fs/list?path=` | 目录列表 |
| GET | `/api/bridge/fs/stat?path=` | 文件/目录元数据 |
| GET | `/api/bridge/fs/read?path=` | 读取文件（文本/二进制 base64） |
| GET | `/api/bridge/fs/download?path=` | 流式下载文件 |
| POST | `/api/bridge/fs/write` | 写入文件 |
| POST | `/api/bridge/fs/mkdir` | 创建目录 |
| POST | `/api/bridge/fs/search` | 搜索文件 |
| GET | `/files/{*path}` | 静态文件服务 |
| GET | `/api/opencode/global/event` | SSE 代理 |
| ANY | 其他 | 转发到 opencode |

## Android 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.0 |
| UI | Jetpack Compose + Material 3 (暗色主题) |
| 架构 | MVVM + Hilt DI + Room + OkHttp |
| 构建 | AGP 8.11.0 / KSP 2.2.0-2.0.2 / Compose BOM 2025.07.00 |
| SDK | minSdk 26 / targetSdk 36 |
| 导航 | Navigation Compose (单 Activity) |
| 网络 | OkHttp (REST + SSE 长连接) |
| 数据库 | Room v9 (每个实例独立 SQLite) |
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
    network/          → OpencodeApiClient, SseClient, SseParser, DTOs
    ui/               → 暗色主题 (opencode palette), MessageBubble, StreamingText
  feature/
    instance/         → 实例列表/添加
    session/          → 工作区/会话/聊天/文件浏览器
    settings/         → 设置页
```

### 数据流

```
REST: OpencodeApiClient → DTOs → toDomain() → Domain → toEntity() → Room → Flow → ViewModel → UI
SSE:  SseClient → SseParser → SseData → EventDispatcher → EventHandler → Room → Flow → UI
```

## Bridge 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Rust (edition 2024) |
| Web | Axum 0.8 + Tower HTTP 0.6 (CORS + tracing) |
| HTTP | Reqwest 0.12 (stream) |
| 异步 | Tokio 1 (full), tokio-stream, tokio-util |
| 配置 | TOML (bridge.toml) |
| CLI | Clap 4 (derive) |

### Bridge 配置

配置文件搜索顺序：CWD → exe 目录 → `~/.opencode/bridge.toml`

```toml
[opencode]
command = "opencode"              # opencode 可执行文件名或路径
hostname = "0.0.0.0"
port = 4096
args = ["serve"]

[server]
port = 4097

[fs]
allowed_paths = []                # 空数组 = 允许所有路径
```

## 构建 & 运行

### Android

```bash
# Android Studio 打开 android/ 目录，直接编译运行
# 或命令行（Windows 需加 --no-daemon）：
cd android
.\gradlew.bat assembleDebug --no-daemon
```

### Bridge

```bash
cd opencode-bridge
cargo build --release
```

运行：

```bash
# 启动 Bridge（自动启动 opencode）
opencode-bridge --config bridge.toml

# opencode 需在 PC 上可用：
opencode serve --hostname 0.0.0.0 --port 4096
```

### 部署

```powershell
# scripts/deploy.bat: 停止 Bridge → 复制二进制 → 重启 → 安装 APK
python scripts\deploy.bat
```

## 项目结构

```
openmate/
  android/               → Android 客户端
  opencode-bridge/       → Bridge Agent (Rust)
  scripts/               → 调试/部署脚本
    deploy.bat           → 部署脚本
    session_tool.py      → opencode REST API 查询工具
    pull_android_db.py   → 从设备拉取 Room DB
    analyze_android_db.py → 分析 Room DB
  AGENTS.md              → Workspace 级指引
  android/AGENTS.md      → Android 架构/约定/API 参考
  opencode-bridge/AGENTS.md → Bridge 架构/约定
```

## License

Private project — not publicly distributed.