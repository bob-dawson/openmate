use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::fs::operations;
use crate::fs::path_guard::PathGuard;
use crate::fs::search::{self, SearchRequest};
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PathQuery {
    pub path: String,
}

#[derive(Debug, Deserialize)]
pub struct MkdirRequest {
    pub path: String,
    #[serde(default)]
    pub recursive: bool,
}

#[derive(Debug, Deserialize)]
pub struct WriteRequest {
    pub path: String,
    pub content: String,
    #[serde(default)]
    pub create_dirs: bool,
}

pub async fn roots() -> impl IntoResponse {
    Json(operations::list_roots())
}

pub async fn list(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let entries = operations::list_dir(&validated_path)?;
    Ok(Json(entries))
}

pub async fn read(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let bytes = operations::read_file(&validated_path)?;

    let ext = validated_path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    let is_text = is_text_extension(&ext) || is_likely_text(&bytes);
    if is_text {
        let text = String::from_utf8_lossy(&bytes).to_string();
        let mime = text_mime_by_ext(&ext);
        Ok(axum::response::Response::builder()
            .status(StatusCode::OK)
            .header("content-type", mime)
            .body(Body::from(text))
            .unwrap()
            .into_response())
    } else {
        let mime = binary_mime_by_ext(&ext);
        let b64 = base64_encode(&bytes);
        Ok(Json(serde_json::json!({ "data": b64, "encoding": "base64", "mime": mime })).into_response())
    }
}

pub async fn download(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let metadata = std::fs::metadata(&validated_path)
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to stat file: {}", e)))?;

    if metadata.is_dir() {
        return Err(AppError::Internal(anyhow::anyhow!("Path is a directory")));
    }

    let ext = validated_path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    let filename = validated_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("download")
        .to_string();

    let mime = guess_mime(&ext);
    let size = metadata.len();

    let file = tokio::fs::File::open(&validated_path)
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to open file: {}", e)))?;

    let stream = tokio_util::io::ReaderStream::with_capacity(file, 64 * 1024);
    let body = Body::from_stream(stream);

    Ok(axum::response::Response::builder()
        .status(StatusCode::OK)
        .header("content-type", mime)
        .header("content-length", size)
        .header(
            "content-disposition",
            format!("inline; filename=\"{}\"", filename),
        )
        .body(body)
        .unwrap()
        .into_response())
}

pub async fn mkdir(
    State(state): State<AppState>,
    Json(body): Json<MkdirRequest>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&body.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    operations::mkdir(&validated_path, body.recursive)?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn search(
    State(state): State<AppState>,
    Json(body): Json<SearchRequest>,
) -> Result<impl IntoResponse, AppError> {
    if !body.path.is_empty() {
        let guard = PathGuard::from_config(&state.config);
        guard
            .validate(&body.path)
            .map_err(|e| AppError::PathNotAllowed(e))?;
    }

    let results = search::search_files(&body).map_err(|e| AppError::Internal(anyhow::anyhow!(e)))?;
    Ok(Json(results))
}

pub async fn stat(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let stat = operations::stat_path(&validated_path)?;
    Ok(Json(stat))
}

pub async fn write(
    State(state): State<AppState>,
    Json(body): Json<WriteRequest>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&body.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    operations::write_file(&validated_path, body.content.as_bytes(), body.create_dirs)?;
    Ok(Json(serde_json::json!({ "success": true })))
}

fn is_text_extension(ext: &str) -> bool {
    matches!(
        ext,
        "txt"
        | "md"
        | "markdown"
        | "mdx"
        | "rst"
        | "adoc"
        | "org"
        | "log"
        | "csv"
        | "tsv"
        | "json"
        | "jsonl"
        | "json5"
        | "yaml"
        | "yml"
        | "toml"
        | "ini"
        | "cfg"
        | "conf"
        | "env"
        | "properties"
        | "xml"
        | "svg"
        | "html"
        | "htm"
        | "css"
        | "scss"
        | "sass"
        | "less"
        | "js"
        | "mjs"
        | "cjs"
        | "jsx"
        | "ts"
        | "tsx"
        | "vue"
        | "svelte"
        | "py"
        | "pyw"
        | "rb"
        | "rs"
        | "go"
        | "java"
        | "kt"
        | "kts"
        | "scala"
        | "groovy"
        | "c"
        | "h"
        | "cpp"
        | "hpp"
        | "cc"
        | "cxx"
        | "cs"
        | "swift"
        | "m"
        | "mm"
        | "sh"
        | "bash"
        | "zsh"
        | "fish"
        | "ps1"
        | "psm1"
        | "bat"
        | "cmd"
        | "sql"
        | "graphql"
        | "gql"
        | "proto"
        | "dart"
        | "lua"
        | "r"
        | "R"
        | "pl"
        | "pm"
        | "ex"
        | "exs"
        | "erl"
        | "hrl"
        | "clj"
        | "cljs"
        | "hs"
        | "ml"
        | "mli"
        | "fs"
        | "fsx"
        | "vim"
        | "el"
        | "lisp"
        | "dockerfile"
        | "makefile"
        | "cmake"
        | "gradle"
        | "gitignore"
        | "dockerignore"
        | "editorconfig"
        | "eslintrc"
        | "prettierrc"
        | "babelrc"
        | "lock"
        | "map"
        | "wasm"
    )
}

fn text_mime_by_ext(ext: &str) -> &'static str {
    match ext {
        "md" | "markdown" | "mdx" => "text/markdown; charset=utf-8",
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "csv" => "text/csv; charset=utf-8",
        "svg" => "image/svg+xml; charset=utf-8",
        "json" | "jsonl" | "json5" => "application/json; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        "yaml" | "yml" => "application/yaml; charset=utf-8",
        "toml" => "application/toml; charset=utf-8",
        _ => "text/plain; charset=utf-8",
    }
}

fn binary_mime_by_ext(ext: &str) -> &'static str {
    match ext {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "ico" => "image/x-icon",
        "pdf" => "application/pdf",
        "zip" => "application/zip",
        "gz" | "gzip" => "application/gzip",
        "tar" => "application/x-tar",
        "rar" => "application/vnd.rar",
        "7z" => "application/x-7z-compressed",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "mp4" => "video/mp4",
        "avi" => "video/x-msvideo",
        "doc" | "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" | "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" | "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        _ => "application/octet-stream",
    }
}

fn is_likely_text(bytes: &[u8]) -> bool {
    if bytes.is_empty() {
        return true;
    }
    let sample_len = bytes.len().min(8192);
    let sample = &bytes[..sample_len];
    let nontext = sample
        .iter()
        .filter(|&&b| !is_text_byte(b))
        .count();
    (nontext as f64) / (sample_len as f64) < 0.1
}

fn is_text_byte(b: u8) -> bool {
    matches!(b, 0x09 | 0x0A | 0x0D | 0x20..=0x7E | 0x80..=0xBF | 0xC0..=0xFD)
}

fn base64_encode(data: &[u8]) -> String {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;
        result.push(CHARS[((triple >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            result.push(CHARS[((triple >> 6) & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
        if chunk.len() > 2 {
            result.push(CHARS[(triple & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
    }
    result
}

fn guess_mime(ext: &str) -> &'static str {
    match ext {
        "txt" => "text/plain; charset=utf-8",
        "md" | "markdown" | "mdx" => "text/markdown; charset=utf-8",
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "csv" => "text/csv; charset=utf-8",
        "svg" => "image/svg+xml; charset=utf-8",
        "json" | "jsonl" | "json5" => "application/json; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        "yaml" | "yml" => "application/yaml; charset=utf-8",
        "toml" => "application/toml; charset=utf-8",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "ico" => "image/x-icon",
        "pdf" => "application/pdf",
        "zip" => "application/zip",
        "gz" | "gzip" => "application/gzip",
        "tar" => "application/x-tar",
        "rar" => "application/vnd.rar",
        "7z" => "application/x-7z-compressed",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "flac" => "audio/flac",
        "aac" => "audio/aac",
        "mp4" => "video/mp4",
        "avi" => "video/x-msvideo",
        "mkv" => "video/x-matroska",
        "mov" => "video/quicktime",
        "webm" => "video/webm",
        "doc" | "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" | "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" | "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
}
