use std::path::Path;
use std::time::UNIX_EPOCH;

use serde::Serialize;

use crate::error::AppError;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DirEntry {
    pub name: String,
    #[serde(rename = "type")]
    pub entry_type: String,
    pub size: u64,
    pub modified: u64,
    pub permissions: String,
    pub is_directory: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RootEntry {
    pub name: String,
    pub path: String,
}

pub fn list_roots() -> Vec<RootEntry> {
    #[cfg(target_os = "windows")]
    {
        let mut roots = Vec::new();
        for letter in b'A'..=b'Z' {
            let drive = format!("{}:/", letter as char);
            if Path::new(&drive).exists() {
                roots.push(RootEntry {
                    name: format!("{}:", letter as char),
                    path: format!("{}:/", letter as char),
                });
            }
        }
        roots
    }
    #[cfg(not(target_os = "windows"))]
    {
        vec![RootEntry {
            name: "/".to_string(),
            path: "/".to_string(),
        }]
    }
}

pub fn list_dir(path: &Path) -> Result<Vec<DirEntry>, AppError> {
    let entries = std::fs::read_dir(path)
        .map_err(|_| AppError::PathNotFound(path.display().to_string()))?;

    let mut result = Vec::new();
    for entry in entries.flatten() {
        let metadata = entry.metadata().map_err(AppError::Io)?;
        let name = entry
            .file_name()
            .to_string_lossy()
            .to_string();
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let permissions = format_permission(&metadata);

        result.push(DirEntry {
            name,
            entry_type: if metadata.is_dir() {
                "directory".to_string()
            } else {
                "file".to_string()
            },
            size: metadata.len(),
            modified,
            permissions,
            is_directory: metadata.is_dir(),
        });
    }

    result.sort_by(|a, b| {
        b.is_directory
            .cmp(&a.is_directory)
            .then(a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });

    Ok(result)
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileStat {
    pub name: String,
    #[serde(rename = "type")]
    pub entry_type: String,
    pub size: u64,
    pub modified: u64,
    pub permissions: String,
    pub is_directory: bool,
}

pub fn stat_path(path: &Path) -> Result<FileStat, AppError> {
    let metadata = std::fs::metadata(path)
        .map_err(|_| AppError::PathNotFound(path.display().to_string()))?;

    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    let modified = metadata
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);

    Ok(FileStat {
        name,
        entry_type: if metadata.is_dir() {
            "directory".to_string()
        } else {
            "file".to_string()
        },
        size: metadata.len(),
        modified,
        permissions: format_permission(&metadata),
        is_directory: metadata.is_dir(),
    })
}

pub fn read_file(path: &Path) -> Result<Vec<u8>, AppError> {
    std::fs::read(path).map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            AppError::PathNotFound(path.display().to_string())
        } else {
            AppError::Io(e)
        }
    })
}

pub fn write_file(path: &Path, content: &[u8], create_dirs: bool) -> Result<(), AppError> {
    if create_dirs {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(AppError::Io)?;
        }
    }
    std::fs::write(path, content).map_err(AppError::Io)
}

pub fn mkdir(path: &Path, recursive: bool) -> Result<(), AppError> {
    if recursive {
        std::fs::create_dir_all(path).map_err(AppError::Io)?;
    } else {
        std::fs::create_dir(path).map_err(AppError::Io)?;
    }
    Ok(())
}

pub fn delete_path(path: &Path, recursive: bool) -> Result<(), AppError> {
    let metadata = std::fs::metadata(path)
        .map_err(|_| AppError::PathNotFound(path.display().to_string()))?;
    
    if metadata.is_dir() {
        if recursive {
            std::fs::remove_dir_all(path).map_err(AppError::Io)?;
        } else {
            std::fs::remove_dir(path).map_err(AppError::Io)?;
        }
    } else {
        std::fs::remove_file(path).map_err(AppError::Io)?;
    }
    Ok(())
}

pub fn rename_path(src: &Path, dst: &Path) -> Result<(), AppError> {
    if !src.exists() {
        return Err(AppError::PathNotFound(src.display().to_string()));
    }
    if dst.exists() {
        return Err(AppError::Internal(anyhow::anyhow!("Destination already exists")));
    }
    std::fs::rename(src, dst).map_err(AppError::Io)?;
    Ok(())
}

fn format_permission(metadata: &std::fs::Metadata) -> String {
    let mut perms = String::new();
    if metadata.permissions().readonly() {
        perms.push_str("r--");
    } else {
        perms.push_str("rw-");
    }
    perms
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_COUNTER: AtomicU64 = AtomicU64::new(0);

    fn unique_test_dir(prefix: &str) -> PathBuf {
        let id = TEST_COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("bridge_{}_{}", prefix, id));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn test_list_dir() {
        let dir = unique_test_dir("list");
        fs::write(dir.join("b.txt"), "hello").unwrap();
        fs::create_dir(dir.join("a_dir")).unwrap();
        fs::write(dir.join("a_file.txt"), "world").unwrap();

        let entries = list_dir(&dir).unwrap();
        assert!(entries.len() >= 3);
        let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"a_dir"));
        assert!(names.contains(&"a_file.txt"));
        assert!(names.contains(&"b.txt"));
        let a_dir = entries.iter().find(|e| e.name == "a_dir").unwrap();
        assert!(a_dir.is_directory);
        let a_file = entries.iter().find(|e| e.name == "a_file.txt").unwrap();
        assert!(!a_file.is_directory);
        assert_eq!(a_file.size, 5);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_list_dir_directories_first() {
        let dir = unique_test_dir("dirfirst");
        fs::create_dir(dir.join("zzz_dir")).unwrap();
        fs::write(dir.join("aaa_file.txt"), "").unwrap();

        let entries = list_dir(&dir).unwrap();
        let dir_idx = entries.iter().position(|e| e.name == "zzz_dir").unwrap();
        let file_idx = entries.iter().position(|e| e.name == "aaa_file.txt").unwrap();
        assert!(dir_idx < file_idx, "directories should come before files");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_list_dir_nonexistent() {
        let result = list_dir(Path::new("/nonexistent_bridge_test_path"));
        assert!(result.is_err());
    }

    #[test]
    fn test_stat_path_file() {
        let dir = unique_test_dir("stat");
        let file_path = dir.join("test.txt");
        fs::write(&file_path, "content here").unwrap();

        let stat = stat_path(&file_path).unwrap();
        assert_eq!(stat.name, "test.txt");
        assert_eq!(stat.entry_type, "file");
        assert!(!stat.is_directory);
        assert_eq!(stat.size, 12);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_stat_path_directory() {
        let dir = unique_test_dir("statdir");
        let sub = dir.join("subdir");
        fs::create_dir_all(&sub).unwrap();

        let stat = stat_path(&sub).unwrap();
        assert_eq!(stat.name, "subdir");
        assert_eq!(stat.entry_type, "directory");
        assert!(stat.is_directory);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_stat_path_nonexistent() {
        let result = stat_path(Path::new("/nonexistent_bridge_test_file"));
        assert!(result.is_err());
    }

    #[test]
    fn test_read_file_text() {
        let dir = unique_test_dir("read");
        let file_path = dir.join("readme.txt");
        fs::write(&file_path, "Hello, Bridge!").unwrap();

        let content = read_file(&file_path).unwrap();
        assert_eq!(String::from_utf8(content).unwrap(), "Hello, Bridge!");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_read_file_binary() {
        let dir = unique_test_dir("readbin");
        let file_path = dir.join("data.bin");
        fs::write(&file_path, &[0x00, 0xFF, 0x42]).unwrap();

        let content = read_file(&file_path).unwrap();
        assert_eq!(content, vec![0x00, 0xFF, 0x42]);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_read_file_nonexistent() {
        let result = read_file(Path::new("/nonexistent_bridge_test_read"));
        assert!(result.is_err());
    }

    #[test]
    fn test_write_file_simple() {
        let dir = unique_test_dir("write");
        let file_path = dir.join("output.txt");

        write_file(&file_path, b"written content", false).unwrap();
        let content = fs::read_to_string(&file_path).unwrap();
        assert_eq!(content, "written content");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_write_file_create_dirs() {
        let dir = unique_test_dir("writedeep");
        let file_path = dir.join("deep").join("nested").join("file.txt");

        write_file(&file_path, b"deep content", true).unwrap();
        let content = fs::read_to_string(&file_path).unwrap();
        assert_eq!(content, "deep content");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_write_file_no_create_dirs_fails() {
        let dir = unique_test_dir("writefail");
        let file_path = dir.join("nonexistent").join("file.txt");

        let result = write_file(&file_path, b"fail", false);
        assert!(result.is_err());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_mkdir_simple() {
        let dir = unique_test_dir("mkdir");
        let new_dir = dir.join("new_dir");

        mkdir(&new_dir, false).unwrap();
        assert!(new_dir.is_dir());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_mkdir_recursive() {
        let dir = unique_test_dir("mkdirr");
        let deep = dir.join("a").join("b").join("c");

        mkdir(&deep, true).unwrap();
        assert!(deep.is_dir());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_mkdir_non_recursive_fails_without_parent() {
        let dir = unique_test_dir("mkdirfail");
        let deep = dir.join("a").join("b");

        let result = mkdir(&deep, false);
        assert!(result.is_err());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_format_permission_readonly() {
        let dir = unique_test_dir("permro");
        let file_path = dir.join("readonly.txt");
        fs::write(&file_path, "test").unwrap();
        let mut perms = fs::metadata(&file_path).unwrap().permissions();
        perms.set_readonly(true);
        fs::set_permissions(&file_path, perms).unwrap();

        let metadata = fs::metadata(&file_path).unwrap();
        assert_eq!(format_permission(&metadata), "r--");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_format_permission_writable() {
        let dir = unique_test_dir("permrw");
        let file_path = dir.join("writable.txt");
        fs::write(&file_path, "test").unwrap();

        let metadata = fs::metadata(&file_path).unwrap();
        assert_eq!(format_permission(&metadata), "rw-");

        let _ = fs::remove_dir_all(&dir);
    }
}
