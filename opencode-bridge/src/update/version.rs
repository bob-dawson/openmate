use serde::Deserialize;
use std::collections::HashMap;

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
    /// Region-keyed mirror base URL lists, e.g. {"cn": [...], "default": [...]}.
    #[serde(default)]
    pub mirrors: Option<HashMap<String, Vec<String>>>,
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

/// "cn" when the OS locale is China (by region, falling back to language), else "default".
pub fn region_key() -> String {
    region_key_from(sys_locale::get_locale().as_deref())
}

pub fn region_key_from(locale: Option<&str>) -> String {
    let Some(locale) = locale.filter(|l| !l.is_empty()) else {
        return "default".to_string();
    };
    let normalized = locale.replace('_', "-");
    let mut parts = normalized.split('-');
    let language = parts.next().unwrap_or("").to_ascii_lowercase();
    // A region subtag is 2 letters (e.g. CN, TW) or 3 digits; "Hans" (script) is not.
    let region = normalized.split('-').skip(1).find(|part| {
        (part.len() == 2 && part.chars().all(|c| c.is_ascii_alphabetic()))
            || (part.len() == 3 && part.chars().all(|c| c.is_ascii_digit()))
    });
    match region {
        Some(region) if region.eq_ignore_ascii_case("CN") => "cn".to_string(),
        Some(_) => "default".to_string(),
        None if language == "zh" => "cn".to_string(),
        None => "default".to_string(),
    }
}

/// Mirror base URLs for [region], most-preferred first; falls back to "default", then to [fallback].
pub fn select_mirrors(
    mirrors: &Option<HashMap<String, Vec<String>>>,
    region: &str,
    fallback: &str,
) -> Vec<String> {
    if let Some(map) = mirrors {
        if let Some(list) = map.get(region).filter(|list| !list.is_empty()) {
            return list.clone();
        }
        if let Some(list) = map.get("default").filter(|list| !list.is_empty()) {
            return list.clone();
        }
    }
    vec![fallback.to_string()]
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
    fn test_region_key_from() {
        assert_eq!(region_key_from(Some("zh-CN")), "cn");
        assert_eq!(region_key_from(Some("zh_CN")), "cn");
        assert_eq!(region_key_from(Some("zh-Hans")), "cn");
        assert_eq!(region_key_from(Some("zh")), "cn");
        assert_eq!(region_key_from(Some("zh-TW")), "default");
        assert_eq!(region_key_from(Some("en-US")), "default");
        assert_eq!(region_key_from(Some("")), "default");
        assert_eq!(region_key_from(None), "default");
    }

    #[test]
    fn test_select_mirrors() {
        let mut map = HashMap::new();
        map.insert("cn".to_string(), vec!["atomgit".to_string(), "github".to_string()]);
        map.insert("default".to_string(), vec!["github".to_string()]);
        let mirrors = Some(map);
        assert_eq!(select_mirrors(&mirrors, "cn", "fallback"), vec!["atomgit", "github"]);
        assert_eq!(select_mirrors(&mirrors, "us", "fallback"), vec!["github"]);
        assert_eq!(select_mirrors(&None, "cn", "fallback"), vec!["fallback"]);
    }

    #[test]
    fn test_manifest_backward_compatible() {
        // Old version.json format (no mirrors) must still parse, so older clients can upgrade.
        let old = r#"{"android":{"version":"0.3.3","tag":"v0.3.3","releasedAt":"2026-09-24"},"bridge":{"version":"0.3.3","tag":"v0.3.3"}}"#;
        let m: VersionManifest = serde_json::from_str(old).unwrap();
        assert_eq!(m.android.as_ref().unwrap().version, "0.3.3");
        assert!(m.bridge.as_ref().unwrap().mirrors.is_none());

        // New format with mirrors parses too.
        let new = r#"{"bridge":{"version":"0.3.4","tag":"v0.3.4","mirrors":{"cn":["a"],"default":["b"]}}}"#;
        let m: VersionManifest = serde_json::from_str(new).unwrap();
        let mirrors = m.bridge.as_ref().unwrap().mirrors.as_ref().unwrap();
        assert_eq!(mirrors.get("cn").unwrap(), &vec!["a".to_string()]);
        assert_eq!(mirrors.get("default").unwrap(), &vec!["b".to_string()]);
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
