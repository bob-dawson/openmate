use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
#[allow(dead_code)]
pub enum AppError {
    #[error("OpenCode is not running")]
    OpencodeNotRunning,

    #[error("OpenCode is already starting")]
    OpencodeAlreadyStarting,

    #[error("OpenCode is already running")]
    OpencodeAlreadyRunning,

    #[error("OpenCode is stopping")]
    OpencodeIsStopping,

    #[error("Failed to start opencode: {0}")]
    OpencodeStartFailed(String),

    #[error("Failed to stop opencode: {0}")]
    OpencodeStopFailed(String),

    #[error("OpenCode request failed: {0}")]
    OpencodeRequestFailed(String),

    #[error("Path not allowed: {0}")]
    PathNotAllowed(String),

    #[error("Path not found: {0}")]
    PathNotFound(String),

    #[error("Not a directory: {0}")]
    NotADirectory(String),

    #[error("Not a git repository: {0}")]
    NotAGitRepo(String),

    #[error("Rate limited")]
    RateLimited,

    #[error("Forbidden")]
    Forbidden,

    #[error("Bad request: {0}")]
    BadRequest(String),

    #[error("Pair not found")]
    PairNotFound,

    #[error("Pair expired")]
    PairExpired,

    #[error("Pair not approved")]
    PairNotApproved,

    #[error("Pair attempts exceeded")]
    PairAttemptsExceeded,

    #[error("Unauthorized")]
    Unauthorized,

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Internal error: {0}")]
    Internal(#[from] anyhow::Error),

    #[error("Database error: {0}")]
    DatabaseError(String),

    #[error("Session not found: {0}")]
    SessionNotFound(String),

    #[error("Message not found: {0}")]
    MessageNotFound(String),

    #[error("Upgrade failed: {0}")]
    UpgradeFailed(String),

    #[error("Upgrade in progress")]
    UpgradeInProgress,

    #[error("Config validation failed: {0}")]
    ConfigValidation(String),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::OpencodeNotRunning => (StatusCode::SERVICE_UNAVAILABLE, self.to_string()),
            AppError::OpencodeAlreadyStarting => (StatusCode::CONFLICT, self.to_string()),
            AppError::OpencodeAlreadyRunning => (StatusCode::CONFLICT, self.to_string()),
            AppError::OpencodeIsStopping => (StatusCode::CONFLICT, self.to_string()),
            AppError::OpencodeStartFailed(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::OpencodeStopFailed(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::OpencodeRequestFailed(_) => (StatusCode::BAD_GATEWAY, self.to_string()),
            AppError::PathNotAllowed(_) => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::PathNotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::NotADirectory(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::NotAGitRepo(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::RateLimited => (StatusCode::TOO_MANY_REQUESTS, self.to_string()),
            AppError::Forbidden => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::BadRequest(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::PairNotFound => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::PairExpired => (StatusCode::GONE, self.to_string()),
            AppError::PairNotApproved => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::PairAttemptsExceeded => (StatusCode::TOO_MANY_REQUESTS, self.to_string()),
            AppError::Unauthorized => (StatusCode::UNAUTHORIZED, self.to_string()),
            AppError::Io(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::DatabaseError(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::SessionNotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::MessageNotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::UpgradeFailed(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            AppError::UpgradeInProgress => (StatusCode::CONFLICT, self.to_string()),
            AppError::ConfigValidation(_) => (StatusCode::BAD_REQUEST, self.to_string()),
        };

        let body = json!({ "error": message });
        (status, axum::Json(body)).into_response()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::StatusCode as AxumStatusCode;

    async fn response_status_and_body(error: AppError) -> (AxumStatusCode, String) {
        let response = error.into_response();
        let status = response.status();
        let body = axum::body::to_bytes(response.into_body(), 1024)
            .await
            .unwrap();
        let text = String::from_utf8(body.to_vec()).unwrap();
        (status, text)
    }

    #[tokio::test]
    async fn test_path_not_allowed_returns_403() {
        let (status, body) = response_status_and_body(AppError::PathNotAllowed("/etc".to_string())).await;
        assert_eq!(status, AxumStatusCode::FORBIDDEN);
        assert!(body.contains("/etc"));
    }

    #[tokio::test]
    async fn test_path_not_found_returns_404() {
        let (status, body) = response_status_and_body(AppError::PathNotFound("/missing".to_string())).await;
        assert_eq!(status, AxumStatusCode::NOT_FOUND);
        assert!(body.contains("/missing"));
    }

    #[tokio::test]
    async fn test_opencode_already_running_returns_409() {
        let (status, _) = response_status_and_body(AppError::OpencodeAlreadyRunning).await;
        assert_eq!(status, AxumStatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn test_opencode_request_failed_returns_502() {
        let (status, _) = response_status_and_body(AppError::OpencodeRequestFailed("timeout".to_string())).await;
        assert_eq!(status, AxumStatusCode::BAD_GATEWAY);
    }

    #[tokio::test]
    async fn test_io_error_returns_500() {
        let (status, _) = response_status_and_body(AppError::Io(std::io::Error::new(std::io::ErrorKind::Other, "disk"))).await;
        assert_eq!(status, AxumStatusCode::INTERNAL_SERVER_ERROR);
    }

    #[tokio::test]
    async fn test_response_body_is_json_with_error_key() {
        let (_, body) = response_status_and_body(AppError::PathNotAllowed("x".to_string())).await;
        let parsed: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert!(parsed.get("error").is_some());
    }
}
