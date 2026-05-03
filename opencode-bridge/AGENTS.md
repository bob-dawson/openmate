# AGENTS.md — opencode-bridge

Rust 反向代理 + 进程管理 + 文件服务，位于 Android 客户端与 opencode serve 之间。

## 架构

```
Android 客户端  ──→  Bridge (port 4097)  ──→  opencode serve (port 4096)
                       │
                       ├─ 代理转发 (fallback)
                       ├─ 进程管理 (auto-start / auto-restart)
                       └─ 扩展 API (/api/bridge/*)
```

## 构建 & 测试

```powershell
cargo build --release          # 产出 target/release/opencode-bridge.exe (~6MB)
cargo test                     # 46 单元 + 10 集成测试
cargo test --test integration  # 仅集成测试
```

## 运行

```powershell
# 无配置文件直接启动（使用全部默认值）
opencode-bridge.exe

# 指定配置
opencode-bridge.exe -c bridge.toml

# 环境变量控制日志级别
RUST_LOG=debug opencode-bridge.exe
```

## 配置文件 (bridge.toml)

搜索顺序：`当前目录/bridge.toml` → `exe所在目录/bridge.toml` → `~/.opencode/bridge.toml`

```toml
[bridge]
port = 4097          # Bridge 监听端口
hostname = "0.0.0.0" # 监听地址

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
| GET | `/api/bridge/status` | Bridge 版本 + opencode 状态 |
| POST | `/api/bridge/opencode/start` | 启动 opencode |
| POST | `/api/bridge/opencode/stop` | 停止 opencode |
| POST | `/api/bridge/opencode/restart` | 重启 opencode |
| GET | `/api/bridge/fs/list?path=` | 目录列表 (目录优先排序) |
| GET | `/api/bridge/fs/stat?path=` | 文件/目录元数据 |
| GET | `/api/bridge/fs/read?path=` | 读文件 (文本→text/plain, 二进制→base64 JSON) |
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
├── main.rs              # 入口、CLI、路由注册
├── lib.rs               # 库入口（导出所有模块）
├── config.rs            # TOML 配置加载 + 默认值
├── error.rs             # AppError 枚举 + HTTP 状态码映射
├── state.rs             # AppState、OpencodeStatus
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
└── integration.rs       # 10 个端到端集成测试 (tower::ServiceExt)
```

## 关键设计决策

- **Fallback 代理**：未匹配路由原样转发给 opencode，Android 端零改动对接（仅改端口 4096→4097）
- **allowed_paths = []** 允许所有路径，Phase 1 LAN 场景靠网络隔离
- **PathGuard** 对不存在的路径用 `find_existing_ancestor()` 向上找存在的父目录做 canonicalize
- **SSE 代理** 用 mpsc channel + ReceiverStream，自动重连（3s/5s 间隔）
- **lib.rs + main.rs** 分离：lib 导出模块供集成测试使用，main 只做入口

## 待处理

见 `TODO.md`
