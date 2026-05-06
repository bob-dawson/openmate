use hmac::{Hmac, Mac};
use sha2::Sha256;

use super::key::SecretKey;

type HmacSha256 = Hmac<Sha256>;

pub struct Token;

impl Token {
    pub fn generate(secret_key: &SecretKey) -> String {
        let random_part = super::key::generate_random_bytes(32);
        let random_hex = super::key::hex_encode(&random_part);

        let signature = compute_hmac(secret_key.as_bytes(), &random_hex);
        let signature_hex = super::key::hex_encode(&signature);

        format!("{}{}", random_hex, signature_hex)
    }

    pub fn validate(secret_key: &SecretKey, token: &str) -> bool {
        if token.len() != 128 {
            return false;
        }

        let random_hex = &token[..64];
        let signature_hex = &token[64..];

        let expected = compute_hmac(secret_key.as_bytes(), random_hex);
        let expected_hex = super::key::hex_encode(&expected);

        constant_time_eq(signature_hex.as_bytes(), expected_hex.as_bytes())
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

#[cfg(test)]
mod tests {
    use super::*;

    fn test_key() -> SecretKey {
        SecretKey::from_bytes(vec![0x42u8; 32])
    }

    #[test]
    fn test_generate_token_length() {
        let key = test_key();
        let token = Token::generate(&key);
        assert_eq!(token.len(), 128);
    }

    #[test]
    fn test_validate_valid_token() {
        let key = test_key();
        let token = Token::generate(&key);
        assert!(Token::validate(&key, &token));
    }

    #[test]
    fn test_validate_wrong_key() {
        let key1 = test_key();
        let key2 = SecretKey::from_bytes(vec![0x24u8; 32]);
        let token = Token::generate(&key1);
        assert!(!Token::validate(&key2, &token));
    }

    #[test]
    fn test_validate_tampered_token() {
        let key = test_key();
        let token = Token::generate(&key);
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
        let t1 = Token::generate(&key);
        let t2 = Token::generate(&key);
        assert_ne!(t1, t2);
        assert!(Token::validate(&key, &t1));
        assert!(Token::validate(&key, &t2));
    }
}
