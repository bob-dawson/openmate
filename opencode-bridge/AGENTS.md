# AGENTS.md — OpenMate Bridge

Rust 反向代理 + 进程管理 + 文件服务 + 认证，位于 Android 客户端与 opencode serve 之间。

## 架构

```
Android 客户端  ──→  Bridge (port 4097)  ──→  opencode serve (port 4096)
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

## 运行

```powershell
# 前台运行（使用全部默认值）
openmate.exe

# 指定配置
openmate.exe -c bridge.toml

# 环境变量控制日志级别
RUST_LOG=debug openmate.exe
```

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

## 配置文件 (bridge.toml)

搜索顺序：`当前目录/bridge.toml` → `exe所在目录/bridge.toml` → `~/.opencode/bridge.toml`

```toml
[bridge]
port = 4097          # Bridge 监听端口
hostname = "0.0.0.0" # 监听地址
auth_enabled = true  # 是否启用认证

[opencode]
binary = "opencode"    # opencode 可执行文件名 (PATH 中或全路径)
hostname = "127.0.0.1" # opencode serve 监听地址
port = 4096            # opencode serve 监听端口
directory = ""         # 工作目录，空=exe所在目录
auto_start = true      # Bridge 启动时自动拉起 opencode
auto_restart = true    # opencode 崩溃后自动重启

[fs]
allowed_paths = []     # 空=允许所有路径
```

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

所有未匹配的路由直接转发给 opencode，Android 端无需改动：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/global/health` | → opencode 健康检查 |
| GET | `/global/event` | → opencode SSE 事件流 |
| GET | `/experimental/session` | → 会话列表 |
| * | `/{*path}` | → 其他所有 opencode API |

## 认证

- `auth_enabled = true` 时，所有非公开路径需要 Bearer token
- 公开路径：`/api/bridge/status`、`/api/bridge/pair/request`、`/api/bridge/pair/confirm`
- 仅 localhost 路径：`/api/bridge/pair/approve`
- Token: HMAC-SHA256 签名，128 字符 hex，存储在 `~/.opencode/bridge_secret_key`
- 代理转发到 opencode 时自动剥离 Authorization 头

## 进程管理

### 崩溃检测（双重机制）

1. **进程退出监控**：`child.wait().await` — opencode 进程退出时立即标记 Crashed
2. **转发失败检测**：`do_proxy()` 中 reqwest 连接被拒时立即标记 Crashed（比等进程退出更快）

### 自动重启

`auto_restart = true` 时，检测到 Crashed 后等 3 秒自动重启，循环重试直到成功。
`auto_restart = false` 时，仅标记状态，不自动重启。

### Windows 特殊处理

- 启动：`cmd /C opencode serve ...`（支持 .cmd/.ps1 包装脚本）
- 停止：`taskkill /F /IM opencode.exe`
- 非 Windows：直接执行 binary / `pkill -f opencode serve`

## 源码结构

```
src/
├── main.rs              # CLI 入口 (install/uninstall/service/approve/reset-token)
├── server.rs            # axum server 启动 + graceful shutdown
├── lib.rs               # 库入口（导出所有模块）
├── config.rs            # TOML 配置加载 + 默认值
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

- **Fallback 代理**：未匹配路由原样转发给 opencode，Android 端零改动对接（仅改端口 4096→4097）
- **allowed_paths = []** 允许所有路径，Phase 1 LAN 场景靠网络隔离
- **PathGuard** 对不存在的路径用 `find_existing_ancestor()` 向上找存在的父目录做 canonicalize
- **SSE 代理** 用 mpsc channel + ReceiverStream，自动重连（3s/5s 间隔）
- **lib.rs + main.rs** 分离：lib 导出模块供集成测试使用，main 只做入口
- **server.rs** 提取为独立模块，供前台模式和服务模式共用
- **认证** HMAC-SHA256 token + PIN 配对，token 绑定 IP
- **服务管理** Windows 用 `windows-service` crate（纯 Rust），Linux 用 systemd unit
