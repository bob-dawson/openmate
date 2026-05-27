use std::path::PathBuf;

const KEY_FILE_NAME: &str = "bridge.key";

#[derive(Clone)]
pub struct SecretKey {
    key: Vec<u8>,
}

impl SecretKey {
    pub fn load_or_generate() -> anyhow::Result<Self> {
        let path = key_file_path()?;
        if path.exists() {
            let hex = std::fs::read_to_string(&path)?;
            let key = hex_to_bytes(hex.trim())?;
            if key.len() != 32 {
                tracing::warn!("Invalid key length, regenerating");
                return Self::generate_and_save(&path);
            }
            tracing::info!("Loaded secret key from {}", path.display());
            Ok(Self { key })
        } else {
            Self::generate_and_save(&path)
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.key
    }

    pub fn from_bytes(key: Vec<u8>) -> Self {
        Self { key }
    }

    pub fn delete_key_file() -> anyhow::Result<()> {
        let path = key_file_path()?;
        if path.exists() {
            std::fs::remove_file(&path)?;
            tracing::info!("Deleted key file {}", path.display());
        }
        Ok(())
    }

    fn generate_and_save(path: &PathBuf) -> anyhow::Result<Self> {
        let key = generate_random_bytes(32);
        let hex = hex_encode(&key);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, &hex)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        }
        tracing::info!("Generated new secret key at {}", path.display());
        Ok(Self { key })
    }
}

fn key_file_path() -> anyhow::Result<PathBuf> {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    Ok(PathBuf::from(home).join(".openmate").join(KEY_FILE_NAME))
}

pub fn generate_random_bytes(len: usize) -> Vec<u8> {
    let mut buf = vec![0u8; len];
    getrandom::fill(&mut buf).expect("Failed to get random bytes");
    buf
}

pub fn base64url_encode(bytes: &[u8]) -> String {
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    base64::engine::Engine::encode(&URL_SAFE_NO_PAD, bytes)
}

pub fn base64url_decode(s: &str) -> anyhow::Result<Vec<u8>> {
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    base64::engine::Engine::decode(&URL_SAFE_NO_PAD, s)
        .map_err(|e| anyhow::anyhow!("Invalid base64url: {}", e))
}

pub fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

pub fn hex_to_bytes(hex: &str) -> anyhow::Result<Vec<u8>> {
    if hex.len() % 2 != 0 {
        anyhow::bail!("Invalid hex length");
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&hex[i..i + 2], 16)
                .map_err(|e| anyhow::anyhow!("Invalid hex: {}", e))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hex_roundtrip() {
        let bytes = vec![0x01, 0x23, 0xab, 0xcd, 0xef];
        let hex = hex_encode(&bytes);
        assert_eq!(hex, "0123abcdef");
        let decoded = hex_to_bytes(&hex).unwrap();
        assert_eq!(decoded, bytes);
    }

    #[test]
    fn test_hex_to_bytes_invalid_length() {
        assert!(hex_to_bytes("abc").is_err());
    }

    #[test]
    fn test_hex_to_bytes_invalid_chars() {
        assert!(hex_to_bytes("zz").is_err());
    }

    #[test]
    fn test_generate_random_bytes_length() {
        let bytes = generate_random_bytes(32);
        assert_eq!(bytes.len(), 32);
    }

    #[test]
    fn test_key_file_path_is_under_openmate() {
        let path = key_file_path().unwrap();
        assert!(path.to_string_lossy().contains(".openmate"));
        assert!(path.to_string_lossy().contains("bridge.key"));
    }
}
