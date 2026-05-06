# Bridge 1.6: 代理请求 Authorization header 剥离

## 目标

转发给 opencode 的请求（REST 代理 + SSE 代理）在转发前必须删除 `Authorization` header，防止 Bridge 的认证 token 泄露给 opencode 进程。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `src/proxy/rest.rs` — 转发前删除 authorization header |
| 修改 | `src/proxy/sse.rs` — SSE 连接不携带 authorization（当前已不携带，但需确认） |

## `src/proxy/rest.rs` 变更

在 header 转发循环中，将 `authorization` 加入跳过列表：

当前代码（第 49-57 行）：

```rust
for (name, value) in headers.iter() {
    let name_str = name.as_str();
    if matches!(name_str, "host" | "connection" | "transfer-encoding") {
        continue;
    }
    if let Ok(v) = value.to_str() {
        req_builder = req_builder.header(name_str, v);
    }
}
```

修改为：

```rust
for (name, value) in headers.iter() {
    let name_str = name.as_str();
    if matches!(name_str, "host" | "connection" | "transfer-encoding" | "authorization") {
        continue;
    }
    if let Ok(v) = value.to_str() {
        req_builder = req_builder.header(name_str, v);
    }
}
```

唯一改动：在 `matches!` 宏中添加 `"authorization"`。

## `src/proxy/sse.rs` 变更

当前 SSE 代理代码（第 56-58 行）创建了一个新的 `reqwest::Client`，请求不带任何自定义 header：

```rust
let client = reqwest::Client::new();
let resp = client
    .get(sse_url)
    .send()
    .await
```

SSE 代理是从 Bridge 内部向 opencode 发起连接，不经过用户的 `Authorization` header，所以**无需修改**。Bridge 自己是 opencode 的客户端，不会把用户的 Bearer token 带进去。

## 测试验证

可以通过以下方式验证：

1. 启动 Bridge（带 auth）
2. 用有效 token 调用 `/api/opencode/session` 等代理路由
3. 检查 opencode 收到的请求中不包含 `Authorization` header

单元测试可在 `rest.rs` 中添加：

```rust
#[test]
fn test_authorization_header_is_stripped() {
    // 验证 header 名称匹配逻辑
    let skipped = ["host", "connection", "transfer-encoding", "authorization"];
    assert!(skipped.contains(&"authorization"));
}
```

但真正验证需要 mock server，集成测试中通过检查 opencode 端收到的请求来确认。
