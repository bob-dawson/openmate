# A.02: 隧道帧协议

> 目标：实现完整的隧道帧序列化/反序列化，覆盖所有帧类型。

## Files

- Modify: `server/relay-gateway/src/tunnel/frame.rs`
- Modify: `server/relay-gateway/src/tunnel/mod.rs`

## 帧类型定义

| type | 方向 | 用途 |
|------|------|------|
| `register` | Bridge→网关 | Bridge 注册（instance_id + token） |
| `request` | 网关→Bridge | HTTP 请求 |
| `response` | Bridge→网关 | HTTP 响应 |
| `sse_open` | 网关→Bridge | 打开 SSE 流 |
| `sse_event` | Bridge→网关 | SSE 事件数据 |
| `sse_close` | 双向 | 关闭 SSE 流 |
| `ping` | Bridge→网关 | 心跳 |
| `pong` | 网关→Bridge | 心跳响应 |
| `error` | 双向 | 错误 |

## Steps

- [ ] **Step 1: 编写帧测试**

在 `src/tunnel/frame.rs` 中添加测试：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_register_frame_roundtrip() {
        let frame = TunnelFrame::register("inst_001", "sometoken123");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "register");
        assert_eq!(parsed.instance_id, Some("inst_001".to_string()));
        assert_eq!(parsed.token, Some("sometoken123".to_string()));
    }

    #[test]
    fn test_request_frame_roundtrip() {
        let mut headers = std::collections::HashMap::new();
        headers.insert("authorization".to_string(), "Bearer xxx".to_string());
        let frame = TunnelFrame::request("req-1", "GET", "/api/bridge/status", Some(headers), None);
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "request");
        assert_eq!(parsed.method, Some("GET".to_string()));
        assert_eq!(parsed.path, Some("/api/bridge/status".to_string()));
    }

    #[test]
    fn test_response_frame_roundtrip() {
        let frame = TunnelFrame::response("req-1", 200, None, Some("{\"ok\":true}".to_string()));
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "response");
        assert_eq!(parsed.status, Some(200));
        assert_eq!(parsed.body, Some("{\"ok\":true}".to_string()));
    }

    #[test]
    fn test_sse_open_frame_roundtrip() {
        let frame = TunnelFrame::sse_open("req-2", "/global/event", None);
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_open");
        assert_eq!(parsed.path, Some("/global/event".to_string()));
    }

    #[test]
    fn test_sse_event_frame_roundtrip() {
        let frame = TunnelFrame::sse_event("req-2", "data: {\"type\":\"session.created\"}\n\n");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_event");
        assert_eq!(parsed.data, Some("data: {\"type\":\"session.created\"}\n\n".to_string()));
    }

    #[test]
    fn test_sse_close_frame_roundtrip() {
        let frame = TunnelFrame::sse_close("req-2");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_close");
        assert_eq!(parsed.request_id, Some("req-2".to_string()));
    }

    #[test]
    fn test_ping_pong_roundtrip() {
        let ping = TunnelFrame::ping();
        let json = serde_json::to_string(&ping).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "ping");

        let pong = TunnelFrame::pong();
        let parsed: TunnelFrame = serde_json::from_str(&serde_json::to_string(&pong).unwrap()).unwrap();
        assert_eq!(parsed.frame_type, "pong");
    }

    #[test]
    fn test_error_frame_roundtrip() {
        let frame = TunnelFrame::error(Some("req-1".to_string()), 503, "bridge offline");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "error");
        assert_eq!(parsed.status, Some(503));
        assert_eq!(parsed.error_message, Some("bridge offline".to_string()));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cargo test -- tunnel::frame`
Expected: 编译失败（构造方法未实现）

- [ ] **Step 3: 实现完整的 TunnelFrame**

替换 `src/tunnel/frame.rs`：

```rust
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize, Deserialize)]
pub struct TunnelFrame {
    #[serde(rename = "type")]
    pub frame_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub instance_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
}

impl TunnelFrame {
    pub fn register(instance_id: &str, token: &str) -> Self {
        TunnelFrame {
            frame_type: "register".to_string(),
            instance_id: Some(instance_id.to_string()),
            token: Some(token.to_string()),
            request_id: None,
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: None,
            error_message: None,
        }
    }

    pub fn request(
        request_id: &str,
        method: &str,
        path: &str,
        headers: Option<HashMap<String, String>>,
        body: Option<String>,
    ) -> Self {
        TunnelFrame {
            frame_type: "request".to_string(),
            request_id: Some(request_id.to_string()),
            method: Some(method.to_string()),
            path: Some(path.to_string()),
            headers,
            body,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn response(
        request_id: &str,
        status: u16,
        headers: Option<HashMap<String, String>>,
        body: Option<String>,
    ) -> Self {
        TunnelFrame {
            frame_type: "response".to_string(),
            request_id: Some(request_id.to_string()),
            status: Some(status),
            headers,
            body,
            method: None,
            path: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn sse_open(
        request_id: &str,
        path: &str,
        headers: Option<HashMap<String, String>>,
    ) -> Self {
        TunnelFrame {
            frame_type: "sse_open".to_string(),
            request_id: Some(request_id.to_string()),
            path: Some(path.to_string()),
            headers,
            method: None,
            body: None,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn sse_event(request_id: &str, data: &str) -> Self {
        TunnelFrame {
            frame_type: "sse_event".to_string(),
            request_id: Some(request_id.to_string()),
            data: Some(data.to_string()),
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn sse_close(request_id: &str) -> Self {
        TunnelFrame {
            frame_type: "sse_close".to_string(),
            request_id: Some(request_id.to_string()),
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn ping() -> Self {
        TunnelFrame {
            frame_type: "ping".to_string(),
            request_id: None,
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn pong() -> Self {
        TunnelFrame {
            frame_type: "pong".to_string(),
            request_id: None,
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn error(request_id: Option<String>, code: u16, message: &str) -> Self {
        TunnelFrame {
            frame_type: "error".to_string(),
            request_id,
            status: Some(code),
            error_message: Some(message.to_string()),
            method: None,
            path: None,
            headers: None,
            body: None,
            data: None,
            instance_id: None,
            token: None,
        }
    }
}
```

> 注意：上面的 `sse_event` 方法有笔误（重复字段），实际实现时直接用完整构造器即可，去掉 `..Default::default()` 和重复字段。这里完整构造器已列出所有字段。

- [ ] **Step 4: 运行测试验证通过**

Run: `cargo test -- tunnel::frame`
Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add server/relay-gateway/src/tunnel/
git commit -m "feat(gateway): implement tunnel frame protocol with all frame types"
```
