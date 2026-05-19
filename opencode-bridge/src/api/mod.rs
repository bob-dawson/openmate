use axum::Router;
use axum::routing::{delete, get, post, put};

use crate::state::AppState;

mod autostart;
mod devices;
mod logs;
mod network;
mod open_ui;
mod qrcode;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/devices", get(devices::list_devices))
        .route(
            "/api/bridge/devices/{device_id}/name",
            put(devices::rename_device),
        )
        .route(
            "/api/bridge/devices/{device_id}",
            delete(devices::delete_device),
        )
        .route("/api/bridge/logs", get(logs::query_logs))
        .route("/api/bridge/logs/stream", get(logs::stream_logs))
        .route(
            "/api/bridge/network/interfaces",
            get(network::list_interfaces),
        )
        .route("/api/bridge/qrcode", get(qrcode::generate_qrcode))
        .route("/api/bridge/autostart", get(autostart::get_autostart))
        .route("/api/bridge/autostart", post(autostart::set_autostart))
        .route("/api/bridge/open-ui", post(open_ui::open_ui))
        .route("/download/openmate.apk", get(open_ui::download_apk))
}
