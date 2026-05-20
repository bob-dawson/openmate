use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub enum GatewayOutgoing {
    Json(TunnelFrame),
    Binary { request_id: String, data: Vec<u8> },
}

#[derive(Debug, Serialize, Deserialize, Default)]
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
    pub fn register(instance_id: &str) -> Self {
        Self {
            frame_type: "register".to_string(),
            request_id: None,
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: None,
            instance_id: Some(instance_id.to_string()),
            token: None,
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
        Self {
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
        Self {
            frame_type: "response".to_string(),
            request_id: Some(request_id.to_string()),
            method: None,
            path: None,
            headers,
            body,
            status: Some(status),
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
        Self {
            frame_type: "sse_open".to_string(),
            request_id: Some(request_id.to_string()),
            method: None,
            path: Some(path.to_string()),
            headers,
            body: None,
            status: None,
            data: None,
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn sse_event(request_id: &str, data: &str) -> Self {
        Self {
            frame_type: "sse_event".to_string(),
            request_id: Some(request_id.to_string()),
            method: None,
            path: None,
            headers: None,
            body: None,
            status: None,
            data: Some(data.to_string()),
            instance_id: None,
            token: None,
            error_message: None,
        }
    }

    pub fn sse_close(request_id: &str) -> Self {
        Self {
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
        Self {
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
        Self {
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

    pub fn response_start(
        request_id: &str,
        status: u16,
        headers: Option<HashMap<String, String>>,
    ) -> Self {
        Self {
            frame_type: "response_start".to_string(),
            request_id: Some(request_id.to_string()),
            status: Some(status),
            headers,
            ..Default::default()
        }
    }

    pub fn response_chunk(request_id: &str) -> Self {
        Self {
            frame_type: "response_chunk".to_string(),
            request_id: Some(request_id.to_string()),
            ..Default::default()
        }
    }

    pub fn response_end(request_id: &str) -> Self {
        Self {
            frame_type: "response_end".to_string(),
            request_id: Some(request_id.to_string()),
            ..Default::default()
        }
    }

    pub fn request_start(
        request_id: &str,
        method: &str,
        path: &str,
        headers: Option<HashMap<String, String>>,
    ) -> Self {
        Self {
            frame_type: "request_start".to_string(),
            request_id: Some(request_id.to_string()),
            method: Some(method.to_string()),
            path: Some(path.to_string()),
            headers,
            ..Default::default()
        }
    }

    pub fn request_end(request_id: &str) -> Self {
        Self {
            frame_type: "request_end".to_string(),
            request_id: Some(request_id.to_string()),
            ..Default::default()
        }
    }

    pub fn error(request_id: Option<String>, code: u16, message: &str) -> Self {
        Self {
            frame_type: "error".to_string(),
            request_id,
            method: None,
            path: None,
            headers: None,
            body: None,
            status: Some(code),
            data: None,
            instance_id: None,
            token: None,
            error_message: Some(message.to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_register_frame_roundtrip() {
        let frame = TunnelFrame::register("inst-1");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "register");
        assert_eq!(parsed.instance_id.as_deref(), Some("inst-1"));
        assert!(parsed.token.is_none());
        assert!(parsed.request_id.is_none());
    }

    #[test]
    fn test_request_frame_roundtrip() {
        let mut headers = HashMap::new();
        headers.insert("content-type".to_string(), "application/json".to_string());
        let frame = TunnelFrame::request("req-1", "POST", "/api/v1/chat", Some(headers.clone()), Some(r#"{"msg":"hello"}"#.to_string()));
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "request");
        assert_eq!(parsed.request_id.as_deref(), Some("req-1"));
        assert_eq!(parsed.method.as_deref(), Some("POST"));
        assert_eq!(parsed.path.as_deref(), Some("/api/v1/chat"));
        assert_eq!(parsed.headers.unwrap(), headers);
        assert_eq!(parsed.body.as_deref(), Some(r#"{"msg":"hello"}"#));
    }

    #[test]
    fn test_response_frame_roundtrip() {
        let frame = TunnelFrame::response("req-2", 200, None, Some("ok".to_string()));
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "response");
        assert_eq!(parsed.request_id.as_deref(), Some("req-2"));
        assert_eq!(parsed.status, Some(200));
        assert_eq!(parsed.body.as_deref(), Some("ok"));
    }

    #[test]
    fn test_sse_frames_roundtrip() {
        let open = TunnelFrame::sse_open("req-3", "/events", None);
        let json = serde_json::to_string(&open).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_open");
        assert_eq!(parsed.path.as_deref(), Some("/events"));

        let event = TunnelFrame::sse_event("req-3", "data: hello\n\n");
        let json = serde_json::to_string(&event).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_event");
        assert_eq!(parsed.data.as_deref(), Some("data: hello\n\n"));

        let close = TunnelFrame::sse_close("req-3");
        let json = serde_json::to_string(&close).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "sse_close");
    }

    #[test]
    fn test_ping_pong_roundtrip() {
        let ping = TunnelFrame::ping();
        let json = serde_json::to_string(&ping).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "ping");

        let pong = TunnelFrame::pong();
        let json = serde_json::to_string(&pong).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "pong");
    }

    #[test]
    fn test_error_frame_roundtrip() {
        let frame = TunnelFrame::error(Some("req-6".to_string()), 500, "internal error");
        let json = serde_json::to_string(&frame).unwrap();
        let parsed: TunnelFrame = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.frame_type, "error");
        assert_eq!(parsed.request_id.as_deref(), Some("req-6"));
        assert_eq!(parsed.status, Some(500));
        assert_eq!(parsed.error_message.as_deref(), Some("internal error"));
    }
}
