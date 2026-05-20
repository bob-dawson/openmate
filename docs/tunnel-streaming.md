# 隧道流式转发数据处理设计

## 1. 概述

网关(Bridge Gateway)和桥接(Bridge Agent)之间通过 WebSocket 隧道转发 HTTP 请求。根据负载特征分为三种处理模式：

| 模式 | 典型路径 | 特点 |
|------|----------|------|
| 全缓冲 (buffered) | 常规 REST API | 请求体 ≤1MB，响应体 ≤10MB，一次性收发 |
| SSE 隧道 (sse) | `/global/event` | 长连接，单向事件流 |
| 流式 (streaming) | `/api/bridge/fs/download`, `/api/bridge/fs/upload`, `/files/*` | 大文件，分块传输 |

本文档详细描述流式模式的设计，以及三个模式之间的边界和交互。

---

## 2. WebSocket 消息格式

### 2.1 消息类型

WS 连接上承载两种 Message 类型：

| WS Message 类型 | 内容 | 用途 |
|-----------------|------|------|
| `Text` | JSON 序列化的 `TunnelFrame` | 控制帧——请求/响应元数据、SSE 事件、心跳 |
| `Binary` | 36 字节 UUID ASCII + 裸字节块 | 数据帧——文件/二进制负载，无 base64 |

### 2.2 Text 帧格式 (`TunnelFrame`)

```json
{
  "type": "<frame_type>",
  "request_id": "<uuid>",
  "method": "GET",
  "path": "/api/xxx",
  "headers": {"key": "value"},
  "body": "...",
  "status": 200,
  "data": "...",
  "instance_id": "...",
  "token": "...",
  "error_message": "..."
}
```

所有字段均为可选（`serde(skip_serializing_if = "Option::is_none")`），按帧类型只填充相关字段。

### 2.3 Binary 帧格式

```
|-- 36 bytes request_id (UUID ASCII) --|-- N bytes chunk data --|
```

- `request_id`：`uuid::Uuid::new_v4().to_string()` 的 ASCII 字节，固定 36 字节
- `chunk data`：裸字节，无编码转换

---

## 3. 帧类型分类

### 3.1 连接管理

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `register` | Bridge → 网关 | 携带 `instance_id`，建立桥接标识 |
| `ping` | Bridge → 网关 | 心跳，30s 间隔 |
| `pong` | 网关 → Bridge | 心跳回复 |

### 3.2 全缓冲请求/响应

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `request` | 网关 → Bridge | 完整 HTTP 请求（含 body） |
| `response` | Bridge → 网关 | 完整 HTTP 响应（含 body） |
| `error` | 双向 | 错误通知（含 status + message） |

### 3.3 SSE 隧道

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `sse_open` | 网关 → Bridge | 开启 SSE 流（含 path + headers） |
| `sse_event` | Bridge → 网关 | SSE 事件数据行（data 字段含原始 SSE 行） |
| `sse_close` | Bridge → 网关 | SSE 流结束 |

### 3.4 流式下载（响应 streaming）

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `request` | 网关 → Bridge | 携带请求元数据 + body（体量小，仍在 JSON 内传输） |
| `response_start` | Bridge → 网关 | 响应起始信号（含 status + headers），后续跟 Binary 数据块 |
| (Binary) | Bridge → 网关 | 负载分块，36 字节 request_id + chunk |
| `response_chunk` | Bridge → 网关 | **预留**。当前为无操作占位帧，未来可用于流控。数据随 Binary 传输 |
| `response_end` | Bridge → 网关 | 响应结束信号 |

### 3.5 流式上传（请求 streaming） [待实现]

| 帧类型 | 方向 | 说明 |
|--------|------|------|
| `request_start` | 网关 → Bridge | 请求元数据（method + path + headers），不含 body |
| (Binary) | 网关 → Bridge | 请求体负载分块，36 字节 request_id + chunk |
| `request_end` | 网关 → Bridge | 请求体结束信号 |
| `response_start` | Bridge → 网关 | 响应起始（同下载流程） |
| (Binary) | Bridge → 网关 | 响应体负载分块 |
| `response_end` | Bridge → 网关 | 响应结束 |

---

## 4. 全缓冲模式 (buffered)

### 4.1 流程

```
client                     gateway                      bridge
  |                          |                            |
  |── HTTP request ─────────►|                            |
  |                          |── Text(request) ──────────►|
  |                          |                            |── 接收完整 body
  |                          |                            |── 构建 reqwest 请求
  |                          |                            |── await 完整响应
  |                          |◄── Text(response) ────────|
  |◄── HTTP response ──────|                            |
```

### 4.2 协议约束

- `body` 字段的类型为 `Option<String>`，只适用于文本/JSON 负载
- 二进制负载（`from_utf8_lossy` 会损坏数据）**不应**走此模式
- 响应体通过 `oneshot` channel 等待，30s 超时

### 4.3 网关侧状态

```
pending_requests: DashMap<String, PendingRequest>
  PendingRequest.responder: oneshot::Sender<TunnelResponse>
```

request_id → responder 映射，收到 response 后取出发送，超时则 remove。

---

## 5. SSE 隧道模式

### 5.1 流程

```
client                     gateway                      bridge
  |                          |                            |
  |── GET /global/event ───►|                            |
  |  (Accept: text/event-strea)                          |
  |                          |── Text(sse_open) ────────►|
  |                          |                            |── reqwest GET 连接 local SSE
  |                          |                            |── bytes_stream() 逐块读取
  |                          |◄── Text(sse_event) ───────|  ← 每收到 \n\n 提取一条事件
  |◄── SSE event ──────────|                            |
  |                          |◄── Text(sse_close) ───────|  ← 流结束
  |◄── SSE end ────────────|                            |
```

### 5.2 帧细节

`sse_event.data` 内容为原始 SSE 数据行（含 `data:` 前缀和 `\n\n` 分隔符），网关在转发给客户端时不额外加工：

```json
{
  "type": "sse_event",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "data": "data: {\"seq\":1,\"type\":\"step.start\"}\n\n"
}
```

### 5.3 网关侧状态

```
pending_sse: DashMap<String, SseStream>
  SseStream.event_tx: mpsc::UnboundedSender<String>
```

### 5.4 桥接侧处理要点

- 使用 `bytes_stream()` 逐块读取 SSE body
- 内部 buffer 累积行，遇 `\n\n` 切分出事件帧立即发送
- 流结束时发送 `sse_close`

### 5.5 keepalive

Axum 默认 SSE KeepAlive 发送 `:` 注释行（15s 间隔），`SseClient` 端的 `SseParser` 忽略注释行，不会影响正常事件流。

---

## 6. 流式下载模式 (response streaming) [已实现]

### 6.1 流程

```
client                     gateway                      bridge
  |                          |                            |
  |── HTTP GET ────────────►|                            |
  |                          |── Text(request) ──────────►|  ← body: None 或小 JSON
  |                          |                            |── reqwest GET/POST local
  |                          |                            |── .bytes_stream() 分块读取
  |                          |◄── Text(response_start) ──|  ← status + headers
  |                          |◄── Binary(rid + chunk1) ──|  ← 数据块
  |                          |◄── Binary(rid + chunk2) ──|  ← 数据块
  |                          |◄── ...                     |
  |                          |◄── Text(response_end) ────|  ← 流结束
  |◄── HTTP streaming body ─|                            |
```

### 6.2 帧细节

**response_start**：
```json
{
  "type": "response_start",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": 200,
  "headers": {"content-type": "application/octet-stream"}
}
```

**Binary**：无 JSON 封装，直接 WS Binary message。
- 前 36 字节：`request_id` 的 UTF-8 编码
- 后续字节：原始 chunk data

**response_end**：
```json
{
  "type": "response_end",
  "request_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 6.3 网关侧状态

```
pending_streams: DashMap<String, PendingStream>
  PendingStream.start_tx: Option<oneshot::Sender<(u16, Vec<(String, String)>)>>
  PendingStream.chunk_tx: mpsc::UnboundedSender<Vec<u8>>
```

### 6.4 桥接侧实现 (`proxy_request_to_local()`)

```
1. 构建 reqwest 请求（同 buffered）
2. 发送 response_start（status + 过滤后的 headers）
3. resp.bytes_stream() 逐块迭代
   每块 → GatewayOutgoing::Binary{request_id, data}
4. 发送 response_end
```

### 6.5 网关侧实现 (`proxy_http_request_streaming()` → `server.rs proxy_handler`)

```
1. 读取完整请求 body → body_str (Option<String>)
2. 创建 PendingStream(start_tx, chunk_tx)
3. 发送 Text(request) 到 Bridge
4. await start_rx → (status, headers)
5. 构建 HTTP Response builder(status, headers)
6. Body::from_stream(chunk_rx → unfold) 返回 axum Response
```

chunk_rx 在 response_end 到来时被 remove（pending_streams 移除 → chunk_tx 不再被写入 → chunk_rx 返回 None → 流自然结束）。

### 6.6 触发路径

```
path_only.starts_with("/api/bridge/fs/download")
|| path_only.starts_with("/files/")
```

---

## 7. 流式上传模式 (request streaming) [待实现]

### 7.1 问题

当前上传走全缓冲模式，存在以下问题：

```
网关 side (server.rs):
  let body_bytes = axum::body::to_bytes(req.into_body(), 100MB).await?;
  let body_str = String::from_utf8_lossy(&body_bytes).to_string();
  // → 100MB 全部缓冲在内存
  // → from_utf8_lossy 损坏二进制数据
  // → body 在 JSON TunnelFrame 中传输，base64 膨胀
```

### 7.2 设计

```
client                     gateway                      bridge
  |                          |                            |
  |── HTTP PUT body ───────►|                            |
  |  分块到达                |                            |
  |                          |── Text(request_start) ────►|  ← method + path + headers
  |                          |── Binary(rid + chunk1) ───►|  ← body 块
  |                          |── Binary(rid + chunk2) ───►|  ← body 块
  |                          |── Binary(rid + chunkN) ───►|  ← body 块
  |                          |── Text(request_end) ──────►|  ← body 结束
  |                          |                            |── reqwest PUT local
  |                          |                            |   Body::from_stream(chunks)
  |                          |◄── Text(response_start) ──|  ← status + headers
  |                          |◄── Binary(rid + chunk) ───|  ← 响应体块（如有）
  |                          |◄── Text(response_end) ────|
  |◄── HTTP response ──────|                            |
```

### 7.3 新增帧

**request_start**：
```json
{
  "type": "request_start",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "method": "PUT",
  "path": "/api/bridge/fs/upload?path=D%3A%5Ctest.txt",
  "headers": {"content-type": "application/octet-stream"}
}
```

**request_end**：
```json
{
  "type": "request_end",
  "request_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 7.4 网关侧实现 (`proxy_handler` 中的 upload 分支)

```
1. 校验 instance_id
2. 创建 PendingUpload(start_tx, chunk_tx)  ← 新状态类型
3. 发送 Text(request_start) 到 Bridge
4. 逐块读取 req.into_body() (axum::body::Body 是 Stream<Item=Result<Bytes, Error>>)
   while let Some(chunk) = body_stream.next().await:
       if chunk.ok → Binary(rid + chunk) 到 Bridge
       if err → 发送 Text(error) + Text(request_end) 并 return
5. 发送 Text(request_end)
6. await start_rx → (status, headers)   ← 等待 Bridge 完成请求并返回响应起始
7. 构建 HTTP Response(status, headers) + Body::from_stream(chunk_rx)
```

### 7.5 桥接侧实现

**新增状态（`client.rs` 或单独模块）**：

```
PendingUploads: DashMap<String, PendingUpload>
  PendingUpload.chunk_tx: mpsc::UnboundedSender<Vec<u8>>
  // 请求完成后，通过现有 download streaming 机制返回响应
```

**`handle_incoming` 新增分支**：

```
"request_start" → {
    // 1. 创建 PendingUpload(chunk_tx)
    // 2. 保存到 PendingUploads
    // 3. spawn 处理任务:
         rx = PendingUpload.chunk_rx
         builder = reqwest::Client::new().request(method, url)
                   .headers(filtered_headers)
                   .body(reqwest::Body::from_stream(
                       // 从 chunk_rx 读，直到 channel 关闭
                       async_stream::stream! {
                           while let Some(chunk) = chunk_rx.recv().await {
                               yield Ok::<_, reqwest::Error>(chunk);
                           }
                       }
                   ))
         resp = builder.send().await
         // 用现有 download streaming 返回响应:
         response_start(status, headers)
         resp.bytes_stream() → Binary chunks
         response_end
}

Binary data → {
    // 从数据中取 36 字节 request_id
    // 查找 PendingUploads[request_id]
    // chunk_tx.send(data[36..])
}

"request_end" → {
    // 查找 PendingUploads[request_id]
    // 关闭 chunk_tx (drop 后 body stream 自动结束)
}
```

### 7.6 网关侧状态新增

```
pending_uploads: DashMap<String, PendingUpload>
  PendingUpload.chunk_tx: mpsc::UnboundedSender<Vec<u8>>
  // 响应 streaming 复用 PendingStream
```

### 7.7 触发路径

```
path_only.starts_with("/api/bridge/fs/upload")
```

---

## 8. 路径检测与策略选择

### 8.1 决策树

```
proxy_handler 收到 HTTP 请求
  │
  ├─ accept: text/event-stream
  │   └─ SSE 隧道 (sse_open/sse_event/sse_close)
  │
  ├─ path starts with /api/bridge/fs/download 或 /files/
  │   └─ 流式下载 (request + response_start/Binary/response_end)
  │
  ├─ path starts with /api/bridge/fs/upload
  │   └─ 流式上传 [待实现]
  │     (request_start/Binary/request_end + response_start/Binary/response_end)
  │
  └─ 其他
      └─ 全缓冲 (request + response)
```

### 8.2 实现位置

`server.rs:proxy_handler()` 中的 `is_sse` / `is_stream` 布尔判断。

---

## 9. 头过滤规则

### 9.1 请求头（client → gateway → bridge → local）

从 client 发来的请求头转发给 bridge 之前，剥离以下头部：

| 头部 | 剥离原因 |
|------|----------|
| `host` | 由 reqwest 根据 URL 自动设置 |
| `connection` | hop-by-hop，不应转发 |
| `transfer-encoding` | HTTP/2 不使用，且 body 已由 axum 解码 |
| `authorization` | 这是 client → gateway 的凭证，opencode 不认识 |
| `x-instance-id` | 网关路由头，不应透传到 bridge |

### 9.2 响应头（local → bridge → gateway → client）

从 local 响应转发给 client 之前，剥离以下头部（`proxy_request_to_local` 中过滤）：

| 头部 | 剥离原因 |
|------|----------|
| `content-encoding` | reqwest 已自动解压 gzip，保留此头会导致 client 二次解压 |
| `content-length` | 流式传输中 body 长度不固定，应由 HTTP/2 chunked 处理 |
| `transfer-encoding` | 同上，hop-by-hop |

### 9.3 实现位置

- 请求头过滤：`proxy_request_to_local()` 和 `proxy_sse_to_tunnel()` 中的 builder 循环
- 响应头过滤：`proxy_request_to_local()` 中构建 resp_headers 时的 filter_map

---

## 10. 状态生命周期对照

### 10.1 网关侧状态总览

```
GatewayState
├── bridges: DashMap<String, BridgeConn>           // 连接级，全生命周期
│     tx: mpsc::UnboundedSender<TunnelFrame>
│     instance_id: String
│     last_heartbeat: Instant
│
├── pending_requests: DashMap<String, PendingRequest>  // 请求级，buffered 模式
│     responder: oneshot::Sender<TunnelResponse>
│
├── pending_sse: DashMap<String, SseStream>            // 请求级，SSE 模式
│     event_tx: mpsc::UnboundedSender<String>
│
├── pending_streams: DashMap<String, PendingStream>    // 请求级，download streaming
│     start_tx: Option<oneshot::Sender<(u16, Vec<(String,String)>)>>
│     chunk_tx: mpsc::UnboundedSender<Vec<u8>>
│
└── pending_uploads: DashMap<String, PendingUpload>    // [待实现] 请求级，upload streaming
      chunk_tx: mpsc::UnboundedSender<Vec<u8>>
```

### 10.2 生命周期时序

**Bridge 连接生命周期：**
```
register → (ping/pong)*N → WS close / heartbeat timeout → remove_bridge
```

**Buffered 请求生命周期：**
```
pending_requests.insert(id, responder)
  → Text(request) → Bridge 处理 → Text(response)
  → responder.send(response) → pending_requests.remove(id)
  → 或 30s 超时 → pending_requests.remove(id)
```

**SSE 请求生命周期：**
```
pending_sse.insert(id, SseStream)
  → Text(sse_open)
  → Text(sse_event)*N
  → Text(sse_close) → pending_sse.remove(id)
```

**流式下载请求生命周期：**
```
pending_streams.insert(id, PendingStream)
  → Text(request)
  → Text(response_start) → start_tx.send(status, headers) → start_tx = None
  → Binary(rid + chunk)*N → chunk_tx.send(chunk)
  → Text(response_end) → pending_streams.remove(id)
  → 或 WS 断开 → chunk_tx 不被继续写入 → chunk_rx 返回 None → 流自然结束
```

**流式上传请求生命周期：** [待实现]
```
pending_uploads.insert(id, PendingUpload)
  → Text(request_start)
  → Binary(rid + chunk)*N → chunk_tx.send(chunk)
  → Text(request_end) → chunk_tx dropped → stream EOF
  → (followed by download streaming for response)
  → pending_uploads.remove(id) + pending_streams 生命周期
```

### 10.3 清理保证

- `response_end` 或 `sse_close` 到达时主动 `remove`
- Bridge WS 断开时，`handle_bridge_ws` 末尾调用 `remove_bridge`，但**不清理 pending 状态**——client 会因 oneshot/mpsc channel 关闭而收到错误、超时或流自然结束
- 心跳超时（60s）只清理 `bridges`，不清理 pending——因为 bridge 断开会自动触发 channel close

---

## 11. 错误处理

### 11.1 帧格式错误

- 网关侧收到非 UTF-8 Text → 打印 warn，跳过
- 网关侧收到 <36 字节 Binary → 打印 warn，跳过
- 桥接侧 JSON 解析失败 → 打印 warn，跳过
- 非预期帧类型 → 打印 warn，跳过

### 11.2 请求层错误

| 场景 | 网关行为 | 桥接行为 |
|------|----------|----------|
| Local server 连接失败 | — | 发送 `error(502)` + `response_end` |
| Local server 返回 4xx/5xx | 按流式/缓冲正常返回 | — |
| 请求超时 (30s) | 返回 504 | 放弃请求，drop channel |
| WS 连接断开 | client 侧收到 channel close → 超时→504 | — |
| Upload body 读取失败 | 发送 `error` + `request_end` | 收到 `request_end` 后完成请求 |

### 11.3 连接层错误

| 场景 | 网关行为 | 桥接行为 |
|------|----------|----------|
| Bridge WS 断开 | pending 状态残留（channel drop 自动通知 client） | 5s 后自动重连 |
| 重连后 instance_id 相同 | 替换旧连接，发 error(409) 通知旧连接 | 正常 register |
| 心跳超时 (60s) | remove_bridge | 连接已断开，重连中 |

### 11.4 数据完整性问题

- **请求体二进制损坏**：上传走流式 Binary 后不再经过 `from_utf8_lossy`，原始字节完整传输。在 `request_start` → Binary chunks → `request_end` 路径中，Binary 消息裸字节被桥接端直接写入 reqwest streaming body
- **响应体二进制损坏**：下载走流式 Binary 后不再经 JSON 序列化，裸字节通过 WS Binary 传输，网关用 `Body::from_stream(chunk_rx)` 直接返回

---

## 12. 关键设计决策

### 12.1 为什么 Binary 消息不包含长度前缀

协议在 WS 层之上不加额外长度头，因为：
- WebSocket 本身提供消息帧边界（每帧独立、完整）
- 每块是一个 WS Binary 消息，receiver 端收到的数据天然分块
- 分块大小由上游控制（`bytes_stream()` 的 buffer、TCP 流控）

### 12.2 为什么 request_id 用 UUID ASCII 而非二进制

- 固定 36 字节，无需长度头
- 便于日志打印、调试
- UUID 全局唯一，无需计数器同步

### 12.3 为什么 `response_chunk` 帧是为空操作

- 最初设计为发送数据前先发送控制帧声明即将到来的数据块
- 当前实现中 Binary 消息本身足以标识数据归属（通过 request_id 前缀）
- 保留 `response_chunk` 作为兼容性占位，未来可用于流控信号

### 12.4 为什么上传不直接复用 `request` 帧 + `body: Option<Vec<u8>>`

- `TunnelFrame` 使用 JSON 序列化，`Vec<u8>` 字段会触发 base64 编码，导致 33% 膨胀
- 大文件全缓冲在网关内存中，100MB 文件会消耗 200MB+（原始 + base64）
- 流式上传让网关边读边转发，O(1) 内存

### 12.5 为什么桥接侧 upload streaming 仍走 `reqwest` 而非裸 HTTP

- 复用现有代理逻辑（header 过滤、URL 构造）
- `reqwest::Body::from_stream()` 原生支持 streaming body
- 保持与 download streaming 一致的错误处理路径

---

## 13. 安全边界

### 13.1 认证

- 网关不验证 token，不验证请求合法性
- `x-instance-id` 是唯一路由依据——网关信任 client 的 instance_id 声明
- 所有认证在 Bridge 端做（HMAC-SHA256 token 验证）
- 因此 `authorization` 头被网关过滤，不传给 opencode

### 13.2 最大体量

- `max_request_body`：100MB（config 中配置）
- 流式上传只由 WS 消息大小限制（tokio-tungstenite 默认 64MB 消息限制，可在配置中调大）
- 文件服务的路径白名单由 Bridge 侧 `path_guard.rs` 控制（当前允许所有路径）

### 13.3 WS 连接安全

- 网关通过 `wss://` 暴露（Nginx TLS termination）
- Bridge 使用 `wss://` 连接（tokio-tungstenite + rustls）
- Bridge 与 local opencode(HTTP) 之间由网络隔离（127.0.0.1:4098）

---

## 14. `GatewayOutgoing` 枚举定义

桥接侧 outbound channel 发送的类型：

```rust
pub enum GatewayOutgoing {
    Json(TunnelFrame),   // → WS Text
    Binary { request_id: String, data: Vec<u8> },  // → WS Binary
}
```

设计理由：
- `client.rs` 的 WS sink 循环统一处理两种变体
- Text 发 JSON 控制帧，Binary 发数据块
- 所有生产者（proxy.rs、sse_proxy.rs）共用同一个 channel

---

## 15. 现有代码与待实现的差异

| 功能 | 文件 | 状态 |
|------|------|------|
| `TunnelFrame::response_start/chunk/end` 构造器 | `frame.rs` (双端) | ✅ 已实现 |
| `proxy_request_to_local()` streaming response | `opencode-bridge/src/gateway/proxy.rs` | ✅ 已实现 |
| `response_chunk` 空帧处理 | `server.rs` WS handler | ✅ 已实现（no-op） |
| `PendingStream` | `server/relay-gateway/src/state.rs` | ✅ 已实现 |
| `proxy_http_request_streaming()` | `server/relay-gateway/src/proxy/http.rs` | ✅ 已实现 |
| `handle_response_start/chunk/end()` | `server/relay-gateway/src/proxy/http.rs` | ✅ 已实现 |
| `server.rs` 路径检测 + 流式分支 | `server/relay-gateway/src/server.rs` | ✅ 已实现 |
| bridge 侧 Binary 消息接收处理 | `opencode-bridge/src/gateway/client.rs` | ❌ 未实现（`_ => {}` 忽略） |
| `request_start`/`request_end` 帧构造器 | `frame.rs` | ❌ |
| `PendingUpload` 状态 + upload 分支 | 双端 | ❌ |
| 桥接侧 body streaming 组装 | 新模块或 proxy.rs | ❌ |
