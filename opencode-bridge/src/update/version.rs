use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct VersionManifest {
    pub bridge: Option<ModuleVersion>,
    pub android: Option<ModuleVersion>,
}

#[derive(Debug, Deserialize)]
pub struct ModuleVersion {
    pub version: String,
    pub tag: String,
    #[serde(rename = "releasedAt")]
    pub released_at: Option<String>,
}

const GATEWAY_URL: &str =
    "https://gateway.clawmate.net/version.json";
const RAW_URL: &str =
    "https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json";

pub async fn fetch_version_manifest_from(
    gateway_url: &str,
    raw_url: &str,
) -> Option<VersionManifest> {
    if let Some(m) = fetch_from(gateway_url).await {
        return Some(m);
    }
    fetch_from(raw_url).await
}

pub async fn fetch_version_manifest() -> Option<VersionManifest> {
    fetch_version_manifest_from(GATEWAY_URL, RAW_URL).await
}

async fn fetch_from(url: &str) -> Option<VersionManifest> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .ok()?;
    let resp = client.get(url).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    resp.json::<VersionManifest>().await.ok()
}

pub fn is_newer(new: &str, old: &str) -> bool {
    let parse = |v: &str| -> Vec<u32> {
        v.trim_start_matches('v')
            .split('.')
            .filter_map(|s| s.parse().ok())
            .collect()
    };
    parse(new) > parse(old)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_newer_major() {
        assert!(is_newer("2.0.0", "1.0.0"));
    }

    #[test]
    fn test_is_newer_patch() {
        assert!(is_newer("1.0.1", "1.0.0"));
    }

    #[test]
    fn test_is_newer_same() {
        assert!(!is_newer("1.0.0", "1.0.0"));
    }

    #[test]
    fn test_is_newer_older() {
        assert!(!is_newer("1.0.0", "2.0.0"));
    }

    #[test]
    fn test_is_newer_with_v_prefix() {
        assert!(is_newer("v1.16.0", "v1.15.0"));
    }

    #[test]
    fn test_parse_manifest() {
        let json = r#"{"android":{"version":"0.1.20","tag":"v0.1.20"},"bridge":{"version":"0.1.19","tag":"v0.1.19","releasedAt":"2026-06-16"}}"#;
        let m: VersionManifest = serde_json::from_str(json).unwrap();
        assert_eq!(m.android.as_ref().unwrap().version, "0.1.20");
        assert_eq!(m.bridge.as_ref().unwrap().version, "0.1.19");
        assert_eq!(m.bridge.as_ref().unwrap().tag, "v0.1.19");
    }

    #[tokio::test]
    async fn test_fetch_jsdelivr_succeeds() {
        let mut server = mockito::Server::new_async().await;
        let _m = server
            .mock("GET", "/version.json")
            .with_status(200)
            .with_body(r#"{"bridge":{"version":"0.1.20","tag":"v0.1.20"}}"#)
            .create_async()
            .await;
        let url = format!("{}/version.json", server.url());
        let result = fetch_version_manifest_from(&url, "https://invalid.example.invalid/v.json").await;
        assert!(result.is_some());
        assert_eq!(result.unwrap().bridge.unwrap().version, "0.1.20");
    }

    #[tokio::test]
    async fn test_fetch_falls_back_to_raw() {
        let mut jsdelivr = mockito::Server::new_async().await;
        let mut raw = mockito::Server::new_async().await;
        jsdelivr
            .mock("GET", "/version.json")
            .with_status(500)
            .create_async()
            .await;
        raw.mock("GET", "/version.json")
            .with_status(200)
            .with_body(r#"{"bridge":{"version":"0.1.20","tag":"v0.1.20"}}"#)
            .create_async()
            .await;
        let result = fetch_version_manifest_from(
            &format!("{}/version.json", jsdelivr.url()),
            &format!("{}/version.json", raw.url()),
        )
        .await;
        assert!(result.is_some());
    }

    #[tokio::test]
    async fn test_fetch_both_fail() {
        let mut s = mockito::Server::new_async().await;
        s.mock("GET", "/v.json").with_status(500).create_async().await;
        let result = fetch_version_manifest_from(
            &format!("{}/v.json", s.url()),
            &format!("{}/v.json", s.url()),
        )
        .await;
        assert!(result.is_none());
    }
}
