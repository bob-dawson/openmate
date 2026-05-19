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
                if !ip.is_loopback() {
                    interfaces.push(NetworkInterface {
                        name,
                        ip: ip.to_string(),
                    });
                }
            }
            IpAddr::V6(_) => {}
        }
    }

    if interfaces.is_empty() {
        interfaces = parse_ipconfig_output()?;
    }

    Ok(interfaces)
}

fn parse_ipconfig_output() -> Result<Vec<NetworkInterface>, AppError> {
    let output = std::process::Command::new("ipconfig")
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run ipconfig: {}", e)))?;

    let text = String::from_utf8_lossy(&output.stdout);
    let mut interfaces = Vec::new();
    let mut current_name = String::new();

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.ends_with(':')
            && !trimmed.contains("IPv4")
            && !trimmed.contains("Subnet")
            && !trimmed.contains("Default")
        {
            current_name = trimmed.trim_end_matches(':').trim().to_string();
        }
        if trimmed.starts_with("IPv4 Address") || trimmed.starts_with("IPv4") {
            if let Some(addr_part) = trimmed.split(':').nth(1) {
                let ip_str = addr_part
                    .trim()
                    .trim_end_matches("(Preferred)")
                    .trim();
                if let Ok(ip) = ip_str.parse::<std::net::Ipv4Addr>() {
                    if !ip.is_loopback() {
                        interfaces.push(NetworkInterface {
                            name: if current_name.is_empty() {
                                "Unknown".to_string()
                            } else {
                                current_name.clone()
                            },
                            ip: ip.to_string(),
                        });
                    }
                }
            }
        }
    }

    Ok(interfaces)
}
