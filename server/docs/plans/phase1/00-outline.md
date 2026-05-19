# Phase 1 实施计划大纲

> Phase 1 拆分为三个子系统，每个子系统独立可测试。按顺序实施。

## 子系统分解

### A. Rust 转发网关（relay-gateway）
全新 Rust crate，实现 HTTP/SSE→WS 隧道转发。
- 步骤 01: 项目脚手架 + 配置加载
- 步骤 02: 隧道帧协议（序列化/反序列化）
- 步骤 03: HMAC-SHA256 token 验证（复用 Bridge 逻辑）
- 步骤 04: Bridge WebSocket 隧道管理（注册/心跳/断线）
- 步骤 05: HTTP 请求代理（HTTP→隧道帧→HTTP）
- 步骤 06: SSE 流代理（SSE→隧道帧→SSE 流）
- 步骤 07: 网关自身 API（/api/gateway/status、health）
- 步骤 08: 集成测试 + 修复

### B. Bridge 隧道模块（opencode-bridge 新增）
在现有 Bridge crate 中新增 gateway 模块。
- 步骤 01: 配置扩展（gateway section）
- 步骤 02: 隧道客户端（WS 上行连接 + register + 心跳）
- 步骤 03: 隧道请求处理（收到隧道帧→本地 HTTP 请求→响应）
- 步骤 04: SSE 隧道处理（收到 sse_open→本地 SSE→sse_event 帧）
- 步骤 05: 集成测试 + 修复

### C. Android 连接策略
修改现有 Android 项目。
- 步骤 01: ServerProfile 增加 remote 类型和 gateway_url
- 步骤 02: ConnectionManager fallback 逻辑
- 步骤 03: 远程配对 UI + 流程
- 步骤 04: 端到端测试

## 实施顺序

```
A.01-03 (网关基础) → B.01-02 (Bridge 连网关) → A.04-07 (网关转发) → B.03-04 (Bridge 隧道处理) → C.01-04 (Android)
```

先 A.01-03 让网关能启动、验证 token，再 B.01-02 让 Bridge 能连上网关注册，然后对接转发逻辑。

## 详细步骤文档

### A. Rust 转发网关
- [A.01: 项目脚手架 + 配置加载](./A01-gateway-scaffold.md)
- [A.02: 隧道帧协议](./A02-tunnel-frame-protocol.md)
- [A.03: HMAC token 验证 + 认证中间件](./A03-auth-middleware.md)
- [A.04: Bridge WS 隧道管理](./A04-bridge-tunnel-management.md)
- [A.05: HTTP 请求代理](./A05-http-proxy.md)
- [A.06: SSE 流代理](./A06-sse-proxy.md)
- [A.07: 网关自身 API](./A07-gateway-api.md)
- [A.08: 心跳清理 + 集成测试](./A08-integration-tests.md)

### B. Bridge 隧道模块
- [B.01: 配置扩展 + 隧道客户端连接](./B01-bridge-gateway-config.md)
- [B.02: 隧道 HTTP 请求处理](./B02-bridge-tunnel-http.md)
- [B.03: 隧道 SSE 流处理](./B03-bridge-tunnel-sse.md)

### C. Android 连接策略
- [C.01: ServerProfile 扩展 + ConnectionManager Fallback](./C01-android-connection-strategy.md)
- [C.02: 远程配对流程](./C02-android-remote-pairing.md)
