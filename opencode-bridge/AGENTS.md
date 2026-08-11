# AGENTS.md — OpenMate Bridge

Rust 反向代理 + 进程管理 + 文件服务 + 认证，位于 Android 客户端与 opencode serve 之间。

## 架构

```
Android 客户端  ──→  Bridge (port 4097)  ──→  opencode serve (port 4098)
                       │
                       ├─ 代理转发 (fallback)
                       ├─ 进程管理 (auto-start / auto-restart)
                       ├─ 认证 (HMAC-SHA256 token + PIN 配对)
                       └─ 扩展 API (/api/bridge/*)
```

## 构建 & 测试

```powershell
cargo build --release          # 产出 target/release/openmate.exe
cargo test                     # 65 单元 + 17 集成测试
cargo test --test integration  # 仅集成测试
```

## 本机更新部署

```powershell
# 一键构建 + 优雅停止 + 替换 + 重启（推荐）
python D:\openmate\scripts\update-bridge.ps1

# 跳过构建，仅停止+替换+重启（已手动 build 过时）
python D:\openmate\scripts\update-bridge.ps1 -SkipBuild
```

脚本流程：
1. `cargo build --release`
2. 复制 binary 到 `D:\openmate\opencode-bridge\release\openmate.exe`
3. 通过 `POST /api/bridge/shutdown` 优雅停止（localhost-only API）
4. 等待进程退出（超时 10s 后 force kill）
5. 启动新进程
6. 验证进程和端口

**重要**：
- 不要用 `Stop-Process -Force` 杀进程，会导致端口占用，下次启动无法绑定原端口
- Binary 部署位置：`D:\openmate\opencode-bridge\release\openmate.exe`
- 优雅停止 API：`POST http://127.0.0.1:{actual_port}/api/bridge/shutdown`（仅 localhost 可调用）

## 运行

```powershell
# 前台运行（配置从 ~/.openmate/bridge.db 自动加载）
openmate.exe

# 环境变量控制日志级别
RUST_LOG=debug openmate.exe
```

> ⚠️ **注意**：Bridge **没有** `-c` 参数，配置全部存储在 SQLite 数据库 `~/.openmate/bridge.db` 的 `config` 表中，**不使用任何 toml 文件**。`~/.opencode/bridge.toml` 仅为历史遗留，不生效。

## 服务管理

```powershell
# 安装为系统服务（Windows: Win32 Service, Linux: systemd）
openmate.exe install

# 卸载服务
openmate.exe uninstall

# 服务模式运行（由系统调用，用户不直接用）
openmate.exe service
```

Windows 服务使用 `windows-service` crate，服务名 `OpenMate`，启动类型=自动，install 后自动启动。
Linux 生成 systemd unit 到 `/etc/systemd/system/openmate.service`，install 后自动 enable + start。

## CLI 命令

| 命令 | 说明 |
|------|------|
| `openmate` | 前台运行 |
| `openmate install` | 安装为系统服务 |
| `openmate uninstall` | 卸载系统服务 |
| `openmate service` | 服务模式运行 |
| `openmate approve <pin>` | 批准配对 PIN |
| `openmate reset-token` | 重置密钥（所有 token 失效） |

## 配置文件（~/.openmate/bridge.db）

配置存储在 SQLite 数据库 `~/.openmate/bridge.db` 的 `config` 表中（key-value 对），由 `Config::load_from_db()` 读取。修改配置可直接用 sqlite3 或 Bridge 的 `/api/bridge/config` API。

```powershell
# 查看全部配置
wsl -d Ubuntu-24.04 -e bash -l -c "sqlite3 ~/.openmate/bridge.db 'SELECT key, value FROM config;'"

# 修改配置（示例）
wsl -d Ubuntu-24.04 -e bash -l -c "sqlite3 ~/.openmate/bridge.db \"UPDATE config SET value='4098' WHERE key='opencode.port';\""
```

关键配置项：

| key | 默认值 | 说明 |
|-----|--------|------|
| `bridge.port` | `4097` | Bridge 监听端口 |
| `bridge.hostname` | `0.0.0.0` | Bridge 监听地址 |
| `opencode.binary` | `opencode` | opencode 可执行文件名（PATH 中或全路径） |
| `opencode.hostname` | `127.0.0.1` | opencode serve 监听地址 |
| `opencode.port` | `4096` | opencode serve 监听端口 |
| `opencode.directory` | `` | 工作目录，空=exe所在目录 |
| `opencode.db_path` | `~/.local/share/opencode/opencode.db` | opencode SQLite 数据库路径（Bridge 直接读此库提供 sync API） |
| `opencode.auto_start` | `true` | Bridge 启动时自动拉起 opencode |
| `opencode.auto_restart` | `true` | opencode 崩溃后自动重启 |
| `opencode.password` | `` | opencode 密码；**为空时自动从 `~/.local/state/opencode/service.json` 读取**（V2 service 模式），再 fallback 到 `~/.local/state/opencode/password` |
| `fs.allowed_paths` | `` | 逗号分隔白名单，空=允许所有路径 |
| `gateway.url` | `` | 网关地址 |
| `gateway.auto_connect` | `true` | 是否自动连接网关 |
| `auth.secret_key` | 自动生成 | HMAC 密钥，首次启动生成并保存 |
| `auth.instance_id` | 自动生成 | Bridge 实例 ID |

## API 路由

### Bridge 自身 API (`/api/bridge/*`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/bridge/status` | Bridge 版本 + opencode 状态 + auth_enabled |
| POST | `/api/bridge/opencode/start` | 启动 opencode |
| POST | `/api/bridge/opencode/stop` | 停止 opencode |
| POST | `/api/bridge/opencode/restart` | 重启 opencode |
| POST | `/api/bridge/pair/request` | 请求配对 PIN |
| POST | `/api/bridge/pair/approve` | 批准 PIN (仅 localhost) |
| POST | `/api/bridge/pair/confirm` | 确认配对，获取 token |
| GET | `/api/bridge/fs/list?path=` | 目录列表 (目录优先排序) |
| GET | `/api/bridge/fs/stat?path=` | 文件/目录元数据 |
| GET | `/api/bridge/fs/read?path=` | 读文件 (文本→text/plain, 二进制→base64 JSON) |
| GET | `/api/bridge/fs/download?path=` | 流式下载文件 |
| PUT | `/api/bridge/fs/upload?path=` | 上传文件 (max 100MB) |
| POST | `/api/bridge/fs/write` | 写文件 `{path, content, createDirs}` |
| POST | `/api/bridge/fs/mkdir` | 创建目录 `{path, recursive}` |
| POST | `/api/bridge/fs/search` | 搜索 `{path, query, searchType, maxResults}` |

### 文件服务 (`/files/*`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/files/{*path}?path=` | 静态文件服务 (目录→JSON列表, 文件→MIME推测) |

### 代理转发 (fallback)

所有未匹配的路由直接转发给 opencode（V2 API），Android 端无需改动：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | → opencode 健康检查（V2） |
| GET | `/api/event` | → opencode SSE 事件流（V2） |
| GET | `/api/session` | → 会话列表（V2） |
| * | `/{*path}` | → 其他所有 opencode API |

> ⚠️ V1 的 `/global/health`、`/global/event`、`/experimental/session` 已废弃，V2 不再提供。

## 认证

- `auth_enabled = true` 时，所有非公开路径需要 Bearer token
- 公开路径：`/api/bridge/status`、`/api/bridge/pair/request`、`/api/bridge/pair/confirm`
- 仅 localhost 路径：`/api/bridge/pair/approve`
- Token: HMAC-SHA256 签名，128 字符 hex，密钥存储在 `~/.openmate/bridge.db` 的 `auth.secret_key` 配置项
- 代理转发到 opencode 时自动剥离 Authorization 头

## 进程管理（V2: opencode2 service 命令）

**V2 模式**（`opencode.binary` 指向 `opencode2`）下，Bridge 通过 `opencode2 service` 子命令管理后台 daemon，而不是直接 spawn 子进程：

1. 启动前先 `service set port <port>` + `service set hostname <hostname>`
2. 用 `service start` 启动后台服务
3. **健康检查轮询**替代 `child.wait()`：定期 `GET /api/health`（带 Basic Auth `opencode:<password>`），3 次连续失败则自动重启

```rust
// src/process/opencode_manager.rs
run_service_cmd(&binary, &["service", "set", "port", &port.to_string()]).await;
run_service_cmd(&binary, &["service", "set", "hostname", &hostname]).await;
run_service_cmd(&binary, &["service", "start"]).await;      // start/stop/restart
check_health_url(&url).await;                                // GET /api/health + Basic Auth
restart_service_loop(&binary, &url, &status).await;         // 3 次失败自动重启
```

**V1 模式**（binary 为 `opencode`）走传统进程管理：`child.wait()` + 转发失败检测双重崩溃检测，`auto_restart` 等 3 秒自动重启。

> ⚠️ opencode2 的密码在 `~/.local/state/opencode/service.json`，health check 用 `Authorization: Basic base64(opencode:<password>)`（用户名固定 `opencode`）。

## 源码结构

```
src/
├── main.rs              # CLI 入口 (install/uninstall/service/approve/reset-token)
├── server.rs            # axum server 启动 + graceful shutdown
├── lib.rs               # 库入口（导出所有模块）
├── config.rs            # 配置加载（读 ~/.openmate/bridge.db 的 config 表）+ 默认值 + 密码读取
├── error.rs             # AppError 枚举 + HTTP 状态码映射
├── state.rs             # AppState、OpencodeStatus
├── service_windows.rs   # Windows 服务 (windows-service crate)
├── service_linux.rs     # Linux 服务 (systemd unit)
├── auth/
│   ├── mod.rs
│   ├── key.rs           # SecretKey 生成/加载
│   ├── token.rs         # Token 生成/验证 (HMAC-SHA256)
│   ├── pair.rs          # PIN 配对流程 (request/approve/confirm)
│   └── middleware.rs    # 认证中间件 (public/localhost/bearer)
├── bridge/router.rs     # Bridge 管理 API handlers
├── process/
│   ├── mod.rs
│   └── opencode_manager.rs  # 进程管理 (start/stop/restart/健康检查/自动重启)
├── proxy/
│   ├── mod.rs
│   ├── rest.rs          # REST 代理 + fallback + 转发失败检测
│   └── sse.rs           # SSE 代理 (mpsc channel + ReceiverStream)
├── fs/
│   ├── mod.rs
│   ├── path_guard.rs    # 路径白名单验证 (空=允许全部)
│   ├── operations.rs    # 文件操作 (list/stat/read/write/mkdir)
│   ├── search.rs        # 文件搜索 (按文件名/内容)
│   └── router.rs        # 文件系统 API handlers
└── files/
    ├── mod.rs
    └── router.rs        # 静态文件服务 (MIME 推测)

tests/
└── integration.rs       # 17 个集成测试 (tower::ServiceExt)
```

## 关键设计决策

- **Fallback 代理**：未匹配路由原样转发给 opencode，Android 端零改动对接（仅改端口 4098→4097）
- **allowed_paths = []** 允许所有路径，Phase 1 LAN 场景靠网络隔离
- **PathGuard** 对不存在的路径用 `find_existing_ancestor()` 向上找存在的父目录做 canonicalize
- **SSE 代理** 用 mpsc channel + ReceiverStream，自动重连（3s/5s 间隔）
- **lib.rs + main.rs** 分离：lib 导出模块供集成测试使用，main 只做入口
- **server.rs** 提取为独立模块，供前台模式和服务模式共用
- **认证** HMAC-SHA256 token + PIN 配对，token 绑定 IP
- **服务管理** Windows 用 `windows-service` crate（纯 Rust），Linux 用 systemd unit
