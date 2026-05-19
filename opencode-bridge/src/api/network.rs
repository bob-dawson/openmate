use axum::response::IntoResponse;
use axum::Json;
use serde::Serialize;
use std::net::IpAddr;

use crate::error::AppError;

#[derive(Serialize)]
pub struct NetworkInterface {
    pub name: String,
    pub ip: String,
}

pub async fn list_interfaces() -> Result<impl IntoResponse, AppError> {
    let interfaces = get_local_ipv4_addresses()?;
    Ok(Json(interfaces))
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
