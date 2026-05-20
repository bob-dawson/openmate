use std::path::Path;

#[derive(Clone)]
pub struct SecretKey(Vec<u8>);

impl SecretKey {
    pub fn from_bytes(bytes: Vec<u8>) -> Self {
        SecretKey(bytes)
    }

    pub fn load_from_file(path: &str) -> anyhow::Result<Self> {
        let p = Path::new(path);
        if !p.exists() {
            tracing::warn!("secret key file '{}' not found, generating ephemeral key", path);
            let mut buf = [0u8; 32];
            getrandom::fill(&mut buf)?;
            return Ok(SecretKey(buf.to_vec()));
        }
        let data = std::fs::read(path)?;
        Ok(SecretKey(data))
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

pub fn validate_token(token: &str, key: &SecretKey) -> bool {
    if token.len() != 128 {
        return false;
    }
    let payload = &token[..64];
    let signature_hex = &token[64..];

    let Ok(expected_sig) = hex::decode(signature_hex) else {
        return false;
    };

    use hmac::{Hmac, Mac};
    use sha2::Sha256;

    type HmacSha256 = Hmac<Sha256>;

    let mut mac = match HmacSha256::new_from_slice(key.as_bytes()) {
        Ok(m) => m,
        Err(_) => return false,
    };
    mac.update(payload.as_bytes());
    mac.verify_slice(&expected_sig).is_ok()
}

mod hex {
    pub fn decode(s: &str) -> Result<Vec<u8>, ()> {
        if s.len() % 2 != 0 {
            return Err(());
        }
        let mut buf = Vec::with_capacity(s.len() / 2);
        for chunk in s.as_bytes().chunks(2) {
            let high = char::from(chunk[0]).to_digit(16).ok_or(())? as u8;
            let low = char::from(chunk[1]).to_digit(16).ok_or(())? as u8;
            buf.push((high << 4) | low);
        }
        Ok(buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_token_wrong_length() {
        let key = SecretKey::from_bytes(vec![0u8; 32]);
        assert!(!validate_token("short", &key));
        assert!(!validate_token(&"a".repeat(64), &key));
        assert!(!validate_token(&"a".repeat(200), &key));
    }

    #[test]
    fn load_from_missing_file_generates_ephemeral() {
        let key = SecretKey::load_from_file("/nonexistent/secret.key").unwrap();
        assert_eq!(key.as_bytes().len(), 32);
    }
}
