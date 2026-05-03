use std::path::PathBuf;

use crate::config::Config;

pub struct PathGuard {
    allowed_paths: Vec<PathBuf>,
}

impl PathGuard {
    pub fn from_config(config: &Config) -> Self {
        PathGuard {
            allowed_paths: config.effective_allowed_paths(),
        }
    }

    pub fn validate(&self, path: &str) -> Result<PathBuf, String> {
        let requested = PathBuf::from(path);

        let canonical = if requested.exists() {
            requested
                .canonicalize()
                .map_err(|e| format!("Failed to resolve path: {}", e))?
        } else {
            let (existing_ancestor, suffix) = find_existing_ancestor(&requested);
            match existing_ancestor {
                Some(ancestor) => {
                    let canonical_ancestor = ancestor
                        .canonicalize()
                        .map_err(|e| format!("Failed to resolve path: {}", e))?;
                    canonical_ancestor.join(suffix)
                }
                None => requested.clone(),
            }
        };

        for allowed in &self.allowed_paths {
            let canonical_allowed = if allowed.exists() {
                allowed
                    .canonicalize()
                    .unwrap_or_else(|_| allowed.clone())
            } else {
                allowed.clone()
            };

            if canonical.starts_with(&canonical_allowed) {
                return Ok(canonical);
            }
        }

        Err(format!("Path '{}' is not within allowed directories", path))
    }
}

fn find_existing_ancestor(path: &PathBuf) -> (Option<PathBuf>, PathBuf) {
    let mut current = path.clone();
    let mut suffix_parts: Vec<std::ffi::OsString> = Vec::new();

    loop {
        if current.exists() {
            let suffix: PathBuf = suffix_parts.into_iter().collect();
            return (Some(current), suffix);
        }
        match (current.file_name(), current.parent()) {
            (Some(name), Some(parent)) => {
                suffix_parts.insert(0, name.to_owned());
                current = parent.to_path_buf();
            }
            _ => {
                let suffix: PathBuf = suffix_parts.into_iter().collect();
                return (None, suffix);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_validate_existing_path() {
        let tmp = std::env::temp_dir().join("bridge_test_existing");
        let _ = fs::create_dir_all(&tmp);

        let guard = PathGuard {
            allowed_paths: vec![tmp.clone()],
        };
        let result = guard.validate(tmp.to_str().unwrap());
        assert!(result.is_ok());

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_validate_nonexistent_child_of_existing() {
        let tmp = std::env::temp_dir().join("bridge_test_child");
        let _ = fs::create_dir_all(&tmp);

        let guard = PathGuard {
            allowed_paths: vec![tmp.clone()],
        };
        let child = tmp.join("nonexistent").join("deep").join("file.txt");
        let result = guard.validate(child.to_str().unwrap());
        assert!(result.is_ok());

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_validate_rejects_outside_path() {
        let tmp = std::env::temp_dir().join("bridge_test_outside");
        let _ = fs::create_dir_all(&tmp);

        let guard = PathGuard {
            allowed_paths: vec![tmp.clone()],
        };
        let outside = std::path::PathBuf::from("C:\\Windows\\System32");
        let result = guard.validate(outside.to_str().unwrap());
        assert!(result.is_err());

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_find_existing_ancestor() {
        let tmp = std::env::temp_dir().join("bridge_test_ancestor");
        let _ = fs::create_dir_all(&tmp);

        let path = tmp.join("a").join("b").join("c");
        let (ancestor, suffix) = find_existing_ancestor(&path);
        assert_eq!(ancestor.unwrap(), tmp);
        assert_eq!(suffix, PathBuf::from("a").join("b").join("c"));

        let _ = fs::remove_dir_all(&tmp);
    }
}
