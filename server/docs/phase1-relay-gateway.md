# Phase 1：Rust 转发网关详细设计

## 1. 目标

实现最小可用的中继服务：App 通过网关透明访问远程 Bridge，Android 网络层代码改动最小化。

## 2. 核心设计原则

- **透明代理**：网关对外暴露 HTTP/SSE 接口，URL 结构与 Bridge 完全一致，Android 只改 baseUrl
- **点对点中继**：每个 Client 与目标 Bridge 建立独立通道，消息不跨客户端共享
- **WS 隧道**：Bridge 通过 WebSocket 上行连接网关，网关将 HTTP 请求封装为隧道帧转发
- **局域网优先**：保留现有局域网直连能力，网关作为后备

## 3. 数据流

```
普通 HTTP 请求:
  Android HTTP → 网关 axum handler → 验证 token → 提取 instance_id
  → 查 state.bridges 取隧道 → 分配 request_id
  → 封装 request 帧 → WS 发给 Bridge
  → Bridge 本地请求 http://127.0.0.1:4097 → 响应原路返回
  → 网关收到 response 帧 → 转 HTTP 响应返回 Android

SSE 请求:
  Android SSE → 网关 axum handler → 验证 token → 提取 instance_id
  → 查隧道 → 分配 request_id
  → 封装 sse_open 帧 → WS 发给 Bridge
  → Bridge 建立本地 SSE 连接 → 事件逐个封装为 sse_event 帧 → 推回网关
  → 网关逐个写入 Android SSE 流
  → Android 断开 → 网关发 sse_close 帧 → 清理
```

## 4. Token 设计

### 4.1 Phase 1 Token（HMAC-SHA256，复用 Bridge 现有机制）

Phase 1 直接复用 Bridge 现有的 HMAC-SHA256 token，不做任何扩展。网关和 Bridge 共享 secret_key。

**Token 结构**（与 Bridge 完全一致）：

```
device_id_hex(16) + random_hex(48) + hmac_hex(64) = 128 字符
```

- 前 16 字符：device_id 的 hex 编码前缀
- 中间 48 字符：随机数
- 后 64 字符：HMAC-SHA256(secret_key, payload) 签名

**网关验证逻辑**：
1. 调用 `Token::validate(secret_key, token)` 验证 HMAC 签名（和 Bridge 相同）
2. 调用 `Token::extract_device_id(token)` 提取 device_id（和 Bridge 相同）
3. 网关不检查 paired_devices 表（无状态、不连数据库），该检查由 Bridge 侧中间件完成
4. instance_id 不在 token 中，由 Bridge 注册时通过 register 帧单独声明

**认证流程**：
- Bridge 配对时生成 token（完全复用现有配对流程）
- Bridge 用 token 连网关 WS → 网关验证 HMAC → 注册 instance_id
- Android 配对获取同一 token → 请求网关携带 Authorization: Bearer token → 网关验证 HMAC 并路由
- 隧道请求到达 Bridge 后，Bridge 自己验证 device_id 是否在 paired_devices 表中

### 4.2 Phase 2 Token（JWT，由 .NET 签发）

Phase 2 引入 .NET 服务后，切换为标准 JWT：

```json
{
  "sub": "user_id",
  "role": "client | bridge",
  "instance_id": "inst_xxx",
  "iat": 1700000000,
  "exp": 1700000000
}
```

网关启动时从 .NET API 拉取 JWT 公钥缓存本地，验证纯内存操作。

## 5. WS 隧道帧协议

Bridge 与网关之间的 WebSocket 帧格式（JSON）：

### 5.1 请求帧（网关→Bridge）

```json
{
  "type": "request",
  "request_id": "req-uuid",
  "method": "GET",
  "path": "/api/bridge/status",
  "headers": { "authorization": "Bearer ..." },
  "body": null
}
```

### 5.2 SSE 打开帧（网关→Bridge）

```json
{
  "type": "sse_open",
  "request_id": "req-uuid",
  "path": "/global/event",
  "headers": { ... }
}
```

### 5.3 响应帧（Bridge→网关）

```json
{
  "type": "response",
  "request_id": "req-uuid",
  "status": 200,
  "headers": { "content-type": "application/json" },
  "body": "{\"status\":\"running\"}"
}
```

### 5.4 SSE 事件帧（Bridge→网关）

```json
{
  "type": "sse_event",
  "request_id": "req-uuid",
  "data": "data: {\"type\":\"session.created\",...}\n\n"
}
```

### 5.5 SSE 关闭帧

```json
{
  "type": "sse_close",
  "request_id": "req-uuid"
}
```

### 5.6 心跳

```json
{ "type": "ping" }
{ "type": "pong" }
```

### 5.7 Bridge 注册帧（Bridge→网关，连接建立后首帧）

```json
{
  "type": "register",
  "instance_id": "inst_xxx",
  "token": "..."
}
```

### 5.8 错误帧

```json
{
  "type": "error",
  "request_id": "req-uuid",
  "code": 503,
  "message": "bridge offline"
}
```

## 6. 连接模型

### 6.1 Bridge 注册

```
Bridge 启动:
  1. 正常启动 HTTP 服务 (4097) — 局域网直连不变
  2. 读取配置中的 gateway_url
  3. WS 连接网关
  4. 发送 register 帧 { instance_id, token }
  5. 网关验证 token → 注册 inst_xxx → Bridge 上线
  6. 开始 30 秒心跳
```

### 6.2 Android 通过网关访问

```
Android 请求:
  1. HTTP 请求到网关，Header 携带 token
  2. 网关验证 token → 提取 instance_id
  3. 查找 instance_id 的 Bridge WS 隧道
  4. Bridge 不在线 → 返回 HTTP 503
  5. Bridge 在线 → 封装为隧道帧转发
  6. 收到响应帧 → 转为标准 HTTP 响应返回 Android
```

### 6.3 内存数据结构

```rust
struct GatewayState {
    // instance_id → Bridge 连接
    bridges: DashMap<String, BridgeConn>,
    // request_id → 等待响应的 Android 连接
    pending_requests: DashMap<String, PendingRequest>,
}

struct BridgeConn {
    ws_tx: mpsc::Sender<TunnelFrame>,
    instance_id: String,
    last_heartbeat: Instant,
}

struct PendingRequest {
    // 普通 HTTP 请求的响应 channel
    tx: oneshot::Sender<TunnelResponse>,
    // 或 SSE 流的 sink
    sse_tx: Option<mpsc::Sender<String>>,
}
```

## 7. 配对流程

### 7.1 局域网配对（现有流程不变）

```
Android 本地连 Bridge → PIN 配对 → Bridge 生成 token
→ baseUrl = http://local-ip:4097
→ 后续局域网不可达时 fallback 到网关
```

### 7.2 远程配对（新增，通过网关）

```
1. Bridge 启动并连接网关:
   Bridge → WS 连网关 → 发送 register 帧（待配对状态，无 token）
   → 网关分配临时 ID → Bridge 进入"等待配对"状态

2. 网关生成配对入口（有效期 10 分钟，一次性）:
   https://gw.openmate.dev/pair/xxxxx
   → Android 扫码/打开链接 → 进入配对页面

3. 确认配对:
   方式 A: Bridge CLI 输入 PIN 确认
   方式 B: Android 输入 Bridge 展示的 PIN 确认

4. 配对完成:
   网关生成 token → 交付 Bridge 和 Android
   Bridge → 存储到本地配置，后续用 token 连网关
   Android → 存储 token，baseUrl = 网关地址，实例类型标记为 remote
```

## 8. 连接策略

### 8.1 Android 实例类型

```
ServerProfile:
  ├─ type: lan | remote
  ├─ lan:  { address, port, gateway_url } + token   # 局域网配对
  ├─ remote: { gateway_url } + token                 # 远程配对
  └─ 共享: instance_id, 名称
```

### 8.2 连接选择逻辑

```
ConnectionManager:

对于 lan 类型实例:
  1. 尝试局域网连接 http://address:port/api/bridge/status
     ├─ 可达 → 使用局域网直连
     └─ 不可达 → 进入步骤 2
  2. 调网关 API 检查 Bridge 是否在线
     GET gateway_url/api/gateway/status?instance_id=inst_xxx
     → { "online": true/false }
     ├─ 在线 → 切换 baseUrl 到 gateway_url
     └─ 不在线 → 提示 Bridge 离线，等待
  3. 在网关模式下，每 60 秒探测局域网
     → 局域网恢复 → 切回局域网直连

对于 remote 类型实例:
  始终使用 gateway_url，不探测局域网
```

### 8.3 Android 端改动汇总

| 组件 | 改动 |
|------|------|
| `ConnectionManager` | 增加 fallback 逻辑：局域网不可达时查网关状态，决定是否切换 |
| `ServerProfile` | 增加 `type` (lan/remote) 和 `gateway_url` 字段 |
| `OpencodeApiClient` | 无改动 |
| `SseClient` | 无改动 |
| token 存储 | 无改动 |

## 9. 断线处理

| 场景 | 网关行为 | Android 行为 |
|------|----------|-------------|
| Bridge WS 断开 | 清理所有 pending 请求，标记 Bridge 离线 | SSE 断开 → 触发现有重连逻辑 |
| Bridge 重连 WS | 重新注册 instance_id | SSE 自动重连 |
| Android 请求时 Bridge 离线 | 返回 HTTP 503 | 提示 Bridge 离线 |
| 网关重启 | Bridge WS 断开 → Bridge 自动重连 | 短暂断线 → 自动恢复 |
| SSE 流中断 | 清理 request_id → 发 sse_close 给 Bridge | SseClient 现有重连逻辑触发 |

## 10. 隧道并发与超时

```
并发:
  - 每个 HTTP 请求分配唯一 request_id (UUID)
  - Bridge 可同时处理多个 request（和现有局域网模式一致）
  - 网关维护 request_id → PendingRequest 映射

超时:
  - 普通 HTTP 请求: 30 秒
  - SSE 流: 无超时，靠心跳维持
  - Bridge 无响应 → 网关返回 504 Gateway Timeout

背压:
  - Bridge 发送过快 → WS 缓冲区满 → 自动反压
  - Android 接收过慢 → HTTP 层 TCP 反压自然传递
```

## 11. 网关 HTTP 接口

### 11.1 透传接口（所有 Bridge API）

所有路径原样转发到 Bridge 隧道：

```
GET|POST|PUT|DELETE|PATCH /{path}
Header: Authorization: Bearer <token>
→ 验证 token → 提取 instance_id → 隧道转发到 Bridge
```

包括但不限于：`/api/bridge/*`、`/session/*`、`/global/event`、`/files/*` 等。

### 11.2 网关自身接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/gateway/status?instance_id=xxx` | 查询 Bridge 是否在线（需 Bearer token，验证 token 中 instance_id 与查询参数一致或为 bridge 角色） |
| GET | `/api/gateway/health` | 网关健康检查 |
| GET | `/ws` | Bridge WebSocket 隧道连接入口 |

## 12. Bridge 端改动

### 12.1 新增模块：隧道上行

```
Bridge 启动:
  1. 正常启动 HTTP 服务 (4097) — 不变
  2. 读取配置 gateway_url（可选，未配置则不上行）
  3. WS 连接 gateway_url/ws
  4. 发送 register 帧
  5. 等待隧道请求

收到隧道请求帧:
  1. 解析 method/path/headers/body
  2. 构造 HTTP 请求发到 http://127.0.0.1:4097（请求自己）
  3. 普通 HTTP → 收到响应 → 封装 response 帧 → 发回网关
  4. SSE 请求 → 建立本地 SSE 连接 → 事件逐个封装 sse_event 帧 → 推回网关

隧道维护:
  - 30 秒发 ping
  - 断线自动重连（指数退避 1s→30s）
```

### 12.2 配置扩展

```toml
[gateway]
url = "wss://gw.openmate.dev/ws"   # 网关地址，空=不上行
auto_connect = true                  # Bridge 启动时自动连网关
```

## 13. Rust 网关模块结构

```
relay-gateway/
├── src/
│   ├── main.rs              # CLI 入口 + 配置加载
│   ├── server.rs            # axum server 启动 + graceful shutdown
│   ├── config.rs            # TOML 配置
│   ├── state.rs             # GatewayState (bridges + pending_requests)
│   ├── auth/
│   │   ├── mod.rs
│   │   ├── hmac.rs          # Phase 1: HMAC-SHA256 token 验证
│   │   ├── jwt.rs           # Phase 2: JWT 公钥验证
│   │   └── pair.rs          # 远程配对流程
│   ├── tunnel/
│   │   ├── mod.rs
│   │   ├── bridge.rs        # Bridge WS 管理 (注册/心跳/断线清理)
│   │   ├── frame.rs         # 隧道帧序列化/反序列化
│   │   └── router.rs        # request_id 路由 + pending 请求管理
│   ├── proxy/
│   │   ├── mod.rs
│   │   ├── http.rs          # HTTP 请求 → 隧道帧 → HTTP 响应
│   │   └── sse.rs           # SSE 请求 → 隧道帧 → SSE 流转发
│   ├── api/
│   │   ├── mod.rs
│   │   └── status.rs        # GET /api/gateway/status
│   └── error.rs             # 错误类型 + HTTP 状态码映射
├── gateway.toml
└── Cargo.toml
```

### 关键依赖

- `axum` — HTTP 框架
- `tokio` — 异步运行时
- `tokio-tungstenite` — WebSocket
- `serde` / `serde_json` — 序列化
- `dashmap` — 并发 HashMap
- `uuid` — request_id 生成
- `hmac` / `sha2` — Phase 1 token 验证
- `jsonwebtoken` — Phase 2 JWT 验证

## 14. 配置文件

```toml
[gateway]
port = 443
hostname = "0.0.0.0"
tls_cert = "/path/to/cert.pem"
tls_key = "/path/to/key.pem"

[auth]
# Phase 1: HMAC 共享密钥（与 Bridge 共享，验证 token）
# 网关启动时加载此密钥，使用与 Bridge 相同的 Token::validate() 验证
secret_key_path = "/path/to/bridge_secret_key"

[tunnel]
heartbeat_interval = 30
heartbeat_timeout = 60
request_timeout = 30
max_request_body = 10485760    # 10MB

[log]
level = "info"
```

## 15. 部署

### 初期部署（Phase 1）

```
单台 VPS:
  - Rust 网关 (443/80)
  - 前面可放 Nginx 做 TLS 终结 + 静态资源

Bridge 分布在各用户电脑上，通过 WS 连网关。
Android 通过 HTTPS 访问网关。
```

### 未来扩展：定向连接

```
1. Android 调网关查询实例状态:
   GET https://gw.openmate.dev/api/gateway/status?instance_id=inst_xxx
   → { "online": true, "gateway": "gw2.openmate.dev" }

2. Android 直接连指定网关:
   baseUrl = https://gw2.openmate.dev
   → Bridge 和 Android 在同一网关实例，无需跨实例路由
   → LB 只做初始查询负载均衡，无需会话保持
```

当多网关实例部署时，Bridge 注册时记录所在网关实例，Android 查询时获取指定网关地址直接连接，避免 LB 层做一致性哈希。
