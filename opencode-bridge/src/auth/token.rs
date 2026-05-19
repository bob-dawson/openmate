use hmac::{Hmac, Mac};
use sha2::Sha256;

use super::key::SecretKey;

type HmacSha256 = Hmac<Sha256>;

pub struct Token;

impl Token {
    pub fn generate(secret_key: &SecretKey, device_id: &str) -> String {
        let device_prefix = &device_id[..16];
        let random_part = super::key::generate_random_bytes(24);
        let random_hex = super::key::hex_encode(&random_part);

        let payload = format!("{}{}", device_prefix, random_hex);
        let signature = compute_hmac(secret_key.as_bytes(), &payload);
        let signature_hex = super::key::hex_encode(&signature);

        format!("{}{}", payload, signature_hex)
    }

    pub fn validate(secret_key: &SecretKey, token: &str) -> bool {
        if token.len() != 128 {
            return false;
        }

        let payload = &token[..64];
        let signature_hex = &token[64..];

        let expected = compute_hmac(secret_key.as_bytes(), payload);
        let expected_hex = super::key::hex_encode(&expected);

        constant_time_eq(signature_hex.as_bytes(), expected_hex.as_bytes())
    }

    pub fn extract_device_id(token: &str) -> Option<String> {
        if token.len() != 128 {
            return None;
        }
        Some(token[..16].to_string())
    }

    pub fn extract_from_header(header_value: &str) -> Option<&str> {
        header_value.strip_prefix("Bearer ")
    }
}

fn compute_hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC key length is valid");
    mac.update(data.as_bytes());
    mac.finalize().into_bytes().to_vec()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

fn hex_decode(hex: &str) -> Option<Vec<u8>> {
    if hex.len() % 2 != 0 {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_DEVICE: &str = "0123456789abcdef";

    fn test_key() -> SecretKey {
        SecretKey::from_bytes(vec![0x42u8; 32])
    }

    #[test]
    fn test_generate_token_length() {
        let key = test_key();
        let token = Token::generate(&key, TEST_DEVICE);
        assert_eq!(token.len(), 128);
    }

    #[test]
    fn test_validate_valid_token() {
        let key = test_key();
        let token = Token::generate(&key, TEST_DEVICE);
        assert!(Token::validate(&key, &token));
    }

    #[test]
    fn test_validate_wrong_key() {
        let key1 = test_key();
        let key2 = SecretKey::from_bytes(vec![0x24u8; 32]);
        let token = Token::generate(&key1, TEST_DEVICE);
        assert!(!Token::validate(&key2, &token));
    }

    #[test]
    fn test_validate_tampered_token() {
        let key = test_key();
        let token = Token::generate(&key, TEST_DEVICE);
        let mut tampered = token.clone();
        let mut bytes: Vec<char> = tampered.chars().collect();
        bytes[0] = if bytes[0] == '0' { '1' } else { '0' };
        tampered = bytes.into_iter().collect();
        assert!(!Token::validate(&key, &tampered));
    }

    #[test]
    fn test_validate_wrong_length() {
        let key = test_key();
        assert!(!Token::validate(&key, "tooshort"));
        assert!(!Token::validate(&key, &"a".repeat(64)));
    }

    #[test]
    fn test_extract_from_header() {
        assert_eq!(
            Token::extract_from_header("Bearer abc123"),
            Some("abc123")
        );
        assert_eq!(Token::extract_from_header("Basic abc123"), None);
        assert_eq!(Token::extract_from_header(""), None);
    }

    #[test]
    fn test_same_key_validates_multiple_tokens() {
        let key = test_key();
        let t1 = Token::generate(&key, TEST_DEVICE);
        let t2 = Token::generate(&key, TEST_DEVICE);
        assert_ne!(t1, t2);
        assert!(Token::validate(&key, &t1));
        assert!(Token::validate(&key, &t2));
    }

    #[test]
    fn test_extract_device_id() {
        let key = test_key();
        let token = Token::generate(&key, "aabbccddeeff0011");
        let device_id = Token::extract_device_id(&token).unwrap();
        assert_eq!(device_id, "aabbccddeeff0011");
    }

    #[test]
    fn test_extract_device_id_invalid_length() {
        assert!(Token::extract_device_id("tooshort").is_none());
        assert!(Token::extract_device_id(&"a".repeat(64)).is_none());
    }

    #[test]
    fn test_hex_decode() {
        let decoded = hex_decode("0123abcdef").unwrap();
        assert_eq!(decoded, vec![0x01, 0x23, 0xab, 0xcd, 0xef]);
    }

    #[test]
    fn test_hex_decode_invalid() {
        assert!(hex_decode("abc").is_none());
        assert!(hex_decode("zz").is_none());
    }
}
