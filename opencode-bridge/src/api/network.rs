use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde::Serialize;
use std::net::IpAddr;

use crate::error::AppError;
use crate::state::AppState;

#[derive(Serialize)]
pub struct NetworkInterface {
    pub name: String,
    pub ip: String,
}

#[derive(Serialize)]
pub struct LanCandidate {
    pub ip: String,
    pub port: u16,
    pub scope: String,
}

#[derive(Serialize)]
pub struct LanCandidatesResponse {
    pub port: u16,
    pub candidates: Vec<LanCandidate>,
}

pub async fn list_interfaces() -> Result<impl IntoResponse, AppError> {
    let interfaces = get_local_ipv4_addresses()?;
    Ok(Json(interfaces))
}

pub async fn lan_candidates(State(state): State<AppState>) -> Result<impl IntoResponse, AppError> {
    let actual = state
        .actual_port
        .load(std::sync::atomic::Ordering::Relaxed);
    let port = if actual == 0 { state.config.bridge.port } else { actual };
    let candidates = get_local_ipv4_addresses()?
        .into_iter()
        .map(|iface| LanCandidate {
            scope: classify_scope(&iface.ip).to_string(),
            ip: iface.ip,
            port,
        })
        .collect();
    Ok(Json(LanCandidatesResponse { port, candidates }))
}

fn classify_scope(ip: &str) -> &'static str {
    let Ok(addr) = ip.parse::<std::net::Ipv4Addr>() else {
        return "other";
    };
    let octets = addr.octets();
    let (a, b) = (octets[0], octets[1]);
    let private = a == 10
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168);
    if private {
        return "private";
    }
    if a == 100 && (64..=127).contains(&b) {
        return "cgnat";
    }
    "other"
}

fn get_local_ipv4_addresses() -> Result<Vec<NetworkInterface>, AppError> {
    let mut interfaces = Vec::new();

    let adapter_list = local_ip_address::list_afinet_netifas()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to list interfaces: {}", e)))?;

    for (name, addr) in adapter_list {
        match addr {
            IpAddr::V4(ip) => {
                if !ip.is_loopback() && !is_link_local(&ip) {
                    interfaces.push(NetworkInterface {
                        name,
                        ip: ip.to_string(),
                    });
                }
            }
            IpAddr::V6(_) => {}
        }
    }

    Ok(interfaces)
}

fn is_link_local(ip: &std::net::Ipv4Addr) -> bool {
    ip.octets()[0] == 169 && ip.octets()[1] == 254
}

#[cfg(test)]
mod tests {
    use super::classify_scope;

    #[test]
    fn classifies_private_ranges() {
        assert_eq!(classify_scope("192.168.5.13"), "private");
        assert_eq!(classify_scope("10.0.0.4"), "private");
        assert_eq!(classify_scope("172.16.3.9"), "private");
        assert_eq!(classify_scope("172.31.255.1"), "private");
    }

    #[test]
    fn classifies_tailscale_cgnat() {
        assert_eq!(classify_scope("100.64.0.1"), "cgnat");
        assert_eq!(classify_scope("100.127.255.254"), "cgnat");
    }

    #[test]
    fn classifies_other_ranges() {
        assert_eq!(classify_scope("8.8.8.8"), "other");
        assert_eq!(classify_scope("172.32.0.1"), "other");
        assert_eq!(classify_scope("100.128.0.1"), "other");
        assert_eq!(classify_scope("not-an-ip"), "other");
    }
}
