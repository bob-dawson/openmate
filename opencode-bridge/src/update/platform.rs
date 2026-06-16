use std::path::PathBuf;

pub fn asset_name_for(os: &str, arch: &str) -> Option<&'static str> {
    match (os, arch) {
        ("windows", _) => Some("openmate.exe"),
        ("linux", "x86_64") => Some("openmate-linux-x86_64"),
        ("linux", "aarch64") => Some("openmate-linux-arm64"),
        ("macos", "aarch64") => Some("openmate-darwin-arm64"),
        _ => None,
    }
}

pub fn asset_name() -> &'static str {
    asset_name_for(std::env::consts::OS, std::env::consts::ARCH)
        .unwrap_or_else(|| panic!("Unsupported platform: {} {}", std::env::consts::OS, std::env::consts::ARCH))
}

pub fn current_exe() -> std::io::Result<PathBuf> {
    std::env::current_exe()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_windows() {
        assert_eq!(asset_name_for("windows", "x86_64"), Some("openmate.exe"));
        assert_eq!(asset_name_for("windows", "aarch64"), Some("openmate.exe"));
    }

    #[test]
    fn test_linux_x86_64() {
        assert_eq!(asset_name_for("linux", "x86_64"), Some("openmate-linux-x86_64"));
    }

    #[test]
    fn test_linux_arm64() {
        assert_eq!(asset_name_for("linux", "aarch64"), Some("openmate-linux-arm64"));
    }

    #[test]
    fn test_macos() {
        assert_eq!(asset_name_for("macos", "aarch64"), Some("openmate-darwin-arm64"));
    }

    #[test]
    fn test_unknown() {
        assert_eq!(asset_name_for("freebsd", "x86_64"), None);
    }
}
