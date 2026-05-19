use axum::Router;
use axum::routing::get;
use axum::response::Html;

use crate::state::AppState;

const INDEX_HTML: &str = include_str!("index.html");
const DOWNLOAD_HTML: &str = include_str!("download.html");

async fn serve_index() -> Html<&'static str> {
    Html(INDEX_HTML)
}

async fn serve_download() -> Html<&'static str> {
    Html(DOWNLOAD_HTML)
}

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/ui/", get(serve_index))
        .route("/ui/index.html", get(serve_index))
        .route("/ui/download", get(serve_download))
}
