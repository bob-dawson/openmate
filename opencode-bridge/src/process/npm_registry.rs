//! opencode 升级时的 npm 源选择。
//!
//! 默认源 `registry.npmjs.org` 在国内拉取一个 ~95MB 的平台包可能超过 5 分钟，
//! 会撑爆 opencode（npm 执行上限 300s）与 Bridge 的超时。这里按区域给出候选源，
//! 升级前实测各源吞吐并选最快的一个，失败再顺延下一个。

use std::time::{Duration, Instant};

use futures::StreamExt;

/// 官方源（兜底）。
pub const NPMJS: &str = "https://registry.npmjs.org";
/// 国内全量同步镜像。
pub const NPMMIRROR: &str = "https://registry.npmmirror.com";
pub const TENCENT: &str = "https://mirrors.cloud.tencent.com/npm";
pub const HUAWEI: &str = "https://repo.huaweicloud.com/repository/npm";

/// 探测时最多读取的字节数（避免 Range 不被支持时下载整个 packument）。
const PROBE_BYTES: usize = 1_048_576;
const PROBE_TIMEOUT: Duration = Duration::from_secs(3);
const PROBE_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

/// 区域候选源，最优先在前。
pub fn candidates(region: &str) -> Vec<&'static str> {
    match region {
        "cn" => vec![NPMMIRROR, TENCENT, HUAWEI, NPMJS],
        _ => vec![NPMJS, NPMMIRROR, TENCENT, HUAWEI],
    }
}

/// 选择升级用的 npm 源列表，首个为实测最快。
///
/// `configured` 非空时置顶（用户显式配置优先）；全部探测失败时退回区域静态顺序。
pub async fn select(configured: &str) -> Vec<String> {
    let region = crate::update::version::region_key();
    let configured = configured.trim().trim_end_matches('/').to_string();

    let candidates: Vec<String> = candidates(&region).into_iter().map(String::from).collect();
    let speeds = futures::future::join_all(candidates.iter().map(|base| probe_speed(base))).await;

    let mut measured: Vec<(f64, String)> = Vec::new();
    let mut unreachable: Vec<String> = Vec::new();
    for (base, speed) in candidates.iter().zip(speeds) {
        match speed {
            Some(speed) => measured.push((speed, base.clone())),
            None => unreachable.push(base.clone()),
        }
    }
    measured.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

    if !configured.is_empty() {
        measured.retain(|(_, base)| base != &configured);
        unreachable.retain(|base| base != &configured);
    }

    tracing::info!(
        "opencode upgrade registry probe (region={}, configured={:?}): {}",
        region,
        configured,
        measured
            .iter()
            .map(|(speed, base)| format!("{}={:.0}B/s", base, speed))
            .chain(unreachable.iter().map(|base| format!("{}=unreachable", base)))
            .collect::<Vec<_>>()
            .join(", ")
    );

    let mut ordered: Vec<String> = Vec::new();
    if !configured.is_empty() {
        ordered.push(configured);
    }
    ordered.extend(measured.into_iter().map(|(_, base)| base));
    ordered.extend(unreachable);
    ordered
}

/// 实测单个源的吞吐（字节/秒）。用 slim packument 探测：与 tarball 同一主机，
/// 体积 1~3MB，实测排序与实际下载速度一致，且无需猜测平台包名。
async fn probe_speed(base: &str) -> Option<f64> {
    let client = reqwest::Client::builder()
        .connect_timeout(PROBE_CONNECT_TIMEOUT)
        .build()
        .ok()?;
    let url = format!("{}/@opencode%2Fcli", base);

    let started = Instant::now();
    let response = tokio::time::timeout(
        PROBE_TIMEOUT,
        client
            .get(&url)
            .header("Accept", "application/vnd.npm.install-v1+json")
            .send(),
    )
    .await
    .ok()?
    .ok()?;
    if !response.status().is_success() {
        return None;
    }

    let mut stream = response.bytes_stream();
    let mut total = 0usize;
    while total < PROBE_BYTES {
        match tokio::time::timeout(PROBE_TIMEOUT, stream.next()).await {
            Ok(Some(Ok(chunk))) => {
                if chunk.is_empty() {
                    break;
                }
                total += chunk.len();
            }
            _ => break,
        }
        if started.elapsed() > PROBE_TIMEOUT * 2 {
            break;
        }
    }

    let elapsed = started.elapsed().as_secs_f64();
    if total == 0 || elapsed <= 0.0 {
        return None;
    }
    Some(total as f64 / elapsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cn_prefers_domestic_mirror() {
        let list = candidates("cn");
        assert_eq!(list.first(), Some(&NPMMIRROR));
        assert_eq!(list.last(), Some(&NPMJS));
    }

    #[test]
    fn default_prefers_official() {
        let list = candidates("default");
        assert_eq!(list.first(), Some(&NPMJS));
        assert!(list.contains(&NPMMIRROR));
    }
}
