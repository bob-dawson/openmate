# AGENTS.md — Relay Gateway

OpenMate 远程访问中继网关。Android 客户端通过此网关中转连接远程 Bridge，解决 NAT/内网穿透问题。

## 架构

```
Android 客户端           网关 (服务器)               Bridge (用户 PC)
     │                       │                          │
     │  ─── HTTPS ───────►   │  ── WS 隧道 ──────────►  │
     │  (api.gateway.net)    │  (register/ping/request)  │
     │                       │                          │
     │  ◄── SSE/HTTP ──────  │  ◄── response/sse_event ─│
```

- **网关不验证 token**，透明转发所有请求
- **网关不做认证**，通过 instance_id 路由到对应 Bridge
- **Bridge 端验证 token**（HMAC-SHA256）

## 服务器部署

- **主机**: `gateway.clawmate.net`（同 `hz3.sogrand.cn`）
- **用户**: `root`
- **路径**: `/data/relay-gateway/`
```

/data/relay-gateway/
├── build.sh              # 编译 + 重启
├── gateway.toml          # 运行时配置（port=6200）
├── relay-gateway         # 编译产物（binary）
├── src/                  # 源码目录（Cargo crate root）
│   ├── Cargo.toml
│   ├── Cargo.lock
│   └── src/              # Rust 源文件
│       ├── main.rs       # CLI 入口
│       ├── lib.rs        # 模块导出
│       ├── config.rs     # TOML 配置
│       ├── server.rs     # Axum HTTP + WebSocket 服务
│       ├── state.rs      # 共享状态（DashMap）
│       ├── error.rs      # 错误类型
│       ├── api/          # 网关自省 API
│       ├── auth/         # HMAC 认证（预留，当前未使用）
│       ├── proxy/        # HTTP/SSE 隧道代理
│       └── tunnel/       # WebSocket 隧道管理
└── tests/
```

## 更新部署

### 1. WSL 编译 Linux 原生 binary → 上传 → 重启（推荐）

```powershell
# WSL 编译
wsl -d Ubuntu-24.04 -- bash -c "source ~/.cargo/env && cd /mnt/d/openmate/server/relay-gateway && cargo build --release"
# 产出: target/release/relay-gateway (Linux ELF)

# 上传 binary 到服务器
scp D:\openmate\server\relay-gateway\target\release\relay-gateway root@gateway.clawmate.net:/data/relay-gateway/

# 重启服务
ssh root@gateway.clawmate.net "systemctl restart relay-gateway"
```

### 2. Windows 编译 → 上传 → 重启

```powershell
# Windows 编译（产出 .exe，仅用于本地调试，服务器需 Linux binary）
cd D:\openmate\server\relay-gateway
cargo build --release
# 产出: target/release/relay-gateway.exe

# 如需部署到服务器，请使用方法 1 (WSL 编译)
```

### 3. 或上传源码 → 服务器编译

```powershell
# 上传单个文件
scp src\server.rs root@gateway.clawmate.net:/data/relay-gateway/src/src/server.rs

# 或上传整个源码目录（排除 target）
scp -r src\* root@gateway.clawmate.net:/data/relay-gateway/src/

# 服务器编译 + 重启
ssh root@gateway.clawmate.net "cd /data/relay-gateway && bash build.sh"
```

### `build.sh` 内容

```bash
source /root/.cargo/env
cd src
cargo build --release || { echo "BUILD FAILED"; exit 1; }
systemctl stop relay-gateway.service
cp target/release/relay-gateway ../
cd ../
systemctl start relay-gateway.service
echo "Finished"
```

### 服务管理

```bash
systemctl status relay-gateway
journalctl -u relay-gateway -f    # 查看实时日志
systemctl restart relay-gateway
```

## 通信协议

### WebSocket 隧道帧（JSON）

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `register` | Bridge→网关 | 注册 instance_id |
| `ping` | Bridge→网关 | 心跳（30s 间隔） |
| `pong` | 网关→Bridge | 心跳回复 |
| `request` | 网关→Bridge | HTTP 请求转发 |
| `response` | Bridge→网关 | HTTP 响应返回 |
| `sse_open` | 网关→Bridge | SSE 流开启 |
| `sse_event` | Bridge→网关 | SSE 事件推送 |
| `sse_close` | Bridge→网关 | SSE 流关闭 |
| `error` | 双向 | 错误通知 |

### HTTP 代理流程

```
客户端                            网关                              Bridge
  │                                │                                  │
  │── GET /api/session?filter=X ──►│                                  │
  │  (X-Instance-Id: xxx)          │                                  │
  │                                │── request(id,GET,/api/session...)─►│
  │                                │                                  │
  │                                │◄─ response(id,200,body) ────────│
  │◄── 200 OK (body) ────────────│                                  │
```

### SSE 代理流程

```
客户端                            网关                              Bridge
  │                                │                                  │
  │── GET /global/event ─────────►│                                  │
  │  (X-Instance-Id: xxx)          │                                  │
  │                                │── sse_open(id,/global/event) ──►│
  │                                │                                  │
  │                                │◄─ sse_event(id,data) ──────────│
  │◄── SSE: data ────────────────│                                  │
  │                                │                                  │
  │                                │◄─ sse_close(id) ────────────────│
  │◄── SSE stream end ───────────│                                  │
```

## 关键设计

- **无状态转发**：网关不持久化任何数据，所有状态在内存 DashMap 中
- **instance_id 路由**：HTTP header `X-Instance-Id` 标识目标 Bridge
- **SSE keepalive**：15s 间隔，用 Axum `:` 注释行（默认 KeepAlive），避免 `data: ping` 被 SseParser 解析为非 JSON 抛异常
- **query string 透传**：`proxy_handler` 用 `path_and_query()` 而非 `path()` 构造转发 URL
- **心跳超时清理**：60s 无心跳的 Bridge 连接自动移除
- **请求超时**：HTTP 请求转发默认 30s 超时
- **最大 body**：10MB

## 源码结构

```
src/
├── main.rs              # CLI 入口（-c 指定配置）
├── lib.rs               # 导出自有模块
├── config.rs            # TOML 配置加载
├── server.rs            # Axum server（WS handler / HTTP proxy fallback）
├── state.rs             # GatewayState（bridges / pending_requests / pending_sse）
├── error.rs             # GatewayError 枚举 + IntoResponse
├── api/
│   ├── mod.rs
│   └── status.rs        # /api/gateway/health, /api/gateway/status
├── auth/
│   ├── mod.rs
│   ├── hmac_auth.rs     # HMAC-SHA256 token 验证（预留）
│   └── middleware.rs    # Axum 中间件（预留）
├── proxy/
│   ├── mod.rs
│   ├── http.rs          # HTTP 请求隧道转发 + oneshot 响应等待
│   └── sse.rs           # SSE 隧道（open_sse_tunnel / handle_sse_event / handle_sse_close）
└── tunnel/
    ├── mod.rs
    ├── frame.rs          # TunnelFrame 定义 + 构造器
    └── bridge.rs         # Bridge 连接管理（register/remove/heartbeat/cleanup）
```
