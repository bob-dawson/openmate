# Bridge API 安全认证设计

## 背景

Bridge Agent 当前零认证，绑定 `0.0.0.0:4097`，CORS 全开，所有 API 裸奔。需要添加认证机制防止局域网和公网滥用。

## 威胁模型

- 同一 LAN 内其他设备/恶意软件调用 Bridge 控制宿主机
- Bridge 端口被暴露到公网后被扫描利用
- 非授权客户端读写宿主机文件系统

## 配对流程

```
┌─────────┐                          ┌─────────┐                    ┌─────────┐
│ Android │                          │ Bridge  │                    │  PC CLI │
└────┬────┘                          └────┬────┘                    └────┬────┘
     │  POST /api/bridge/pair/request     │                              │
     │ ──────────────────────────────────>│                              │
     │         { "pin": "482901" }        │                              │
     │<──────────────────────────────────│                              │
     │                                    │                              │
     │  （Android 显示 PIN，等待用户授权）  │                              │
     │                                    │  opencode-bridge approve     │
     │                                    │       482901                 │
     │                                    │<─────────────────────────────│
     │                                    │  { "approved": true }        │
     │                                    │─────────────────────────────>│
     │                                    │                              │
     │  POST /api/bridge/pair/confirm     │                              │
     │  { "pin": "482901" }              │                              │
     │ ──────────────────────────────────>│                              │
     │   { "token": "a3f8..." }          │                              │
     │<──────────────────────────────────│                              │
     │                                    │                              │
     │  后续请求 Authorization: Bearer a3f8...                           │
```

## PIN 规则

| 规则 | 值 |
|------|------|
| 格式 | 6 位随机数字 |
| 有效期 | 5 分钟 |
| 最大尝试次数 | 3 次失败后作废 |
| 一次性 | confirm 成功后立即销毁 |
| 频率限制 | 同一 IP 30 秒内只能请求 1 次 |

## Token 机制

### 密钥管理

- 密钥文件路径：`~/.openmate/bridge.key`
- 文件权限：0600（仅当前用户可读写）
- 格式：256-bit 随机 hex 字符串（64 字符）
- 首次启动自动生成并写入文件
- 后续启动从文件加载

### Token 生成

```
token_random = 256-bit 随机 hex（32 字节）
token_signature = HMAC-SHA256(secret_key, token_random)
token = token_random + token_signature  （128 hex 字符）
```

### Token 验证（无状态）

```
收到 Bearer token:
  1. 拆分 token_random（前64字符）和 token_signature（后64字符）
  2. 重新计算 HMAC-SHA256(secret_key, token_random)
  3. 常量时间比较签名
  4. 匹配 → 放行，不匹配 → 401
```

### 重置密钥

命令：`opencode-bridge reset-token`
- 删除 `~/.openmate/bridge.key`
- 清空内存中的 pending pairs
- 下次启动生成新密钥，所有旧 token 自动失效

## Bridge 内存数据结构

```rust
struct PendingPair {
    pin: String,           // "482901"
    ip: String,            // 发起请求的客户端 IP
    approved: bool,        // 是否已被 CLI 授权
    attempts: u32,         // confirm 尝试次数
    created_at: Instant,   // 超时判断
}

// 内存中存储
pending_pairs: HashMap<String, PendingPair>  // key = PIN
```

Bridge 重启后 `pending_pairs` 清空（不影响已签发的 token）。

## API 设计

### 未认证（公开端点）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/bridge/pair/request` | 请求配对，返回 PIN |
| POST | `/api/bridge/pair/confirm` | 提交 PIN，返回 token |
| GET | `/api/bridge/status` | 健康检查（保持不变） |

### 仅 localhost 可调用

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/bridge/pair/approve` | 授权 PIN，仅接受 127.0.0.1 |

### 需 Token 认证

所有其他路由：
- `/api/bridge/opencode/*`（进程控制）
- `/api/bridge/fs/*`（文件系统）
- `/files/*`（静态文件）
- `/api/opencode/*`（代理）
- `/global/event`（SSE 代理）
- 兜底代理

## 认证中间件

```
请求进入
  ├─ 路径匹配 /api/bridge/pair/*  → 放行（公开）
  ├─ 路径匹配 /api/bridge/status  → 放行（健康检查）
  ├─ 来源是 127.0.0.1 且路径是 /api/bridge/pair/approve → 放行
  ├─ Authorization: Bearer <token> 验证通过 → 放行
  └─ 其他 → 401 Unauthorized
```

### 代理请求的 Token 剥离

转发给 opencode 的请求（REST 代理 + SSE 代理）在转发前**必须删除 `Authorization` header**，避免 Bridge 的认证 token 泄露给 opencode 进程。

```rust
// 代理转发前
let mut forwarded_request = request.clone();
forwarded_request.headers_mut().remove("authorization");
```

## bridge.toml 变更

```toml
[bridge]
port = 4097
hostname = "0.0.0.0"
# 新增：
# auth_enabled = true   # 设为 false 可关闭认证（开发模式），默认 true
```

`auth_enabled = false` 时跳过所有 token 检查，行为与当前一致。

## Bridge CLI 子命令

| 命令 | 说明 |
|------|------|
| `opencode-bridge approve <PIN>` | 授权待处理的配对请求 |
| `opencode-bridge reset-token` | 删除密钥文件，使所有 token 失效 |

`approve` 通过 localhost 调用 Bridge 的 `/api/bridge/pair/approve` 接口。

## CORS

保持 `CorsLayer::permissive()`。Token 认证已足够防止跨站滥用。CORS 收紧可作为后续优化。

## Android 端变更

### 新增 API 方法

- `OpencodeApiClient.pairRequest()` → `{ "pin": "482901" }`
- `OpencodeApiClient.pairConfirm(pin: String)` → `{ "token": "a3f8..." }`

### 连接流程变更

```
AddInstanceViewModel 连接时:
  1. 先调 /api/bridge/status 确认 Bridge 存活
  2. 调任意需要认证的接口
     - 200 → 已有有效 token，正常工作
     - 401 → 进入配对流程
  3. 配对流程:
     a. 调 pair/request → 获得 PIN
     b. 显示 PairingScreen（PIN + 等待授权提示）
     c. 轮询 pair/confirm（每 2 秒）直到成功或超时
     d. 成功 → 存储 token 到 EncryptedSharedPreferences
```

### Token 存储

- 使用 `EncryptedSharedPreferences` 存储 token
- Key: 实例 ID（instance ID）
- 后续所有 HTTP 请求自动附加 `Authorization: Bearer <token>` header

### OkHttpClient 拦截器

添加 `AuthInterceptor`：
- 从 EncryptedSharedPreferences 读取 token
- 为每个请求添加 `Authorization: Bearer <token>` header
- 收到 401 响应 → 触发重新配对流程

## 传输层安全

**当前阶段：明文 HTTP，不加 TLS。**

理由：
- Token 认证已防止未授权访问
- LAN 环境下明文嗅探风险可接受
- 自签名证书增加 Android 端复杂度（自定义 TrustManager），当前阶段不值得

风险：LAN 内同一网段的攻击者可嗅探 Bearer token，获取后可调用所有 API。

**未来规划：** 如需加强，可引入自签名证书 + 证书指纹固定：
- Bridge 启动时生成自签名证书（`~/.openmate/bridge.crt`）
- 首次配对时通过 PIN 流程传递证书 SHA-256 指纹
- Android 端自定义 TrustManager，只信任指纹匹配的证书

## 安全性分析

| 攻击向量 | 防护措施 |
|----------|----------|
| 远程暴力破解 PIN | 6 位数字（100万种）+ 5 分钟超时 + 3 次尝试锁定 |
| 远程调用 approve | 仅接受 localhost 请求 |
| 窃取 token 后长期滥用 | token 绑定密钥，`reset-token` 可一键失效 |
| 公网扫描 | 无 token 无法访问任何敏感 API |
| 已知 token 重放 | Token 足够长（128 hex），无法伪造签名 |
| Bridge 重启后 token 泄漏 | 密钥持久化，token 签名验证，重启不影响 |
