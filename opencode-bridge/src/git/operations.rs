use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Serialize;

use crate::error::AppError;

#[cfg(target_os = "windows")]
fn strip_unc_prefix(path: &Path) -> PathBuf {
    let s = path.to_string_lossy();
    if s.starts_with(r"\\?\") {
        PathBuf::from(&s[4..])
    } else {
        path.to_path_buf()
    }
}

#[cfg(not(target_os = "windows"))]
fn strip_unc_prefix(path: &Path) -> PathBuf {
    path.to_path_buf()
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitStatusEntry {
    pub path: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
}

pub fn git_status(dir: &Path) -> Result<Vec<GitStatusEntry>, AppError> {
    let dir = strip_unc_prefix(dir);
    let output = Command::new("git")
        .args(["status", "--porcelain"])
        .current_dir(&dir)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git: {}", e)))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        if stderr.contains("not a git repository") || stderr.contains("not in a git repo") {
            return Err(AppError::NotAGitRepo(dir.display().to_string()));
        }
        return Err(AppError::Internal(anyhow::anyhow!("git status failed: {}", stderr)));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_porcelain_v1(&stdout))
}

fn parse_porcelain_v1(output: &str) -> Vec<GitStatusEntry> {
    let mut entries = Vec::new();
    for line in output.lines() {
        let line = line.trim_end();
        if line.len() < 4 {
            continue;
        }
        let x = line.chars().next().unwrap_or(' ');
        let y = line.chars().nth(1).unwrap_or(' ');

        if x == '?' && y == '?' {
        let path = line[3..].to_string();
        if path.ends_with('/') {
            continue;
        }
        entries.push(GitStatusEntry {
            path,
            status: "untracked".to_string(),
            old_path: None,
        });
            continue;
        }

        if x == 'R' || y == 'R' {
            let rest = &line[3..];
            let parts: Vec<&str> = rest.splitn(2, " -> ").collect();
            if parts.len() == 2 {
                entries.push(GitStatusEntry {
                    path: parts[1].to_string(),
                    status: "renamed".to_string(),
                    old_path: Some(parts[0].to_string()),
                });
            } else {
                entries.push(GitStatusEntry {
                    path: rest.to_string(),
                    status: "renamed".to_string(),
                    old_path: None,
                });
            }
            continue;
        }

        let status = match (x, y) {
            (' ', 'M') | ('M', ' ') | ('M', 'M') => "modified",
            ('A', _) | (' ', 'A') => "added",
            ('D', _) | (' ', 'D') => "deleted",
            ('U', _) | (' ', 'U') => "unmerged",
            _ => "modified",
        };

        let path = line[3..].to_string();
        entries.push(GitStatusEntry {
            path,
            status: status.to_string(),
            old_path: None,
        });
    }
    entries
}

pub fn git_diff(file: &Path) -> Result<String, AppError> {
    let file = strip_unc_prefix(file);
    let file_str = file.display().to_string();
    let parent = file.parent().unwrap_or(Path::new("."));

    let repo_root_output = Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
        .current_dir(parent)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git rev-parse: {}", e)))?;

    if !repo_root_output.status.success() {
        return Err(AppError::NotAGitRepo(parent.display().to_string()));
    }

    let repo_root = String::from_utf8_lossy(&repo_root_output.stdout).trim().to_string();
    let repo_root_path = PathBuf::from(&repo_root);
    let relative = if file.is_absolute() {
        file.strip_prefix(&repo_root_path)
            .map(|p| p.to_path_buf())
            .or_else(|_| {
                let file_normalized: String = file.to_string_lossy().replace('\\', "/");
                let root_normalized: String = repo_root_path.to_string_lossy().replace('\\', "/");
                PathBuf::from(&file_normalized)
                    .strip_prefix(&PathBuf::from(&root_normalized))
                    .map(|p| p.to_path_buf())
            })
            .unwrap_or_else(|_| file.clone())
            .display()
            .to_string()
    } else {
        file_str
    };

    let output = Command::new("git")
        .args(["diff", "--no-color", "HEAD", "--", &relative])
        .current_dir(&repo_root)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git diff: {}", e)))?;

    let diff = String::from_utf8_lossy(&output.stdout).to_string();

    if diff.trim().is_empty() {
        let staged_output = Command::new("git")
            .args(["diff", "--no-color", "--cached", "--", &relative])
            .current_dir(&repo_root)
            .output()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git diff --cached: {}", e)))?;

        let staged = String::from_utf8_lossy(&staged_output.stdout).to_string();

        if staged.trim().is_empty() {
            let full_path = if file.is_absolute() {
                file.to_path_buf()
            } else {
                Path::new(&repo_root).join(&relative)
            };
            if full_path.exists() {
                return Ok(format_untracked_diff(&relative, &full_path));
            }
            return Ok(String::new());
        }

        return Ok(staged);
    }

    Ok(diff)
}

fn format_untracked_diff(relative_path: &str, full_path: &Path) -> String {
    let content = std::fs::read_to_string(full_path).unwrap_or_default();
    let lines: Vec<&str> = content.lines().collect();
    let count = lines.len();
    let mut out = String::new();
    out.push_str(&format!("diff --git a/{} b/{}\n", relative_path, relative_path));
    out.push_str("new file mode 100644\n");
    out.push_str(&format!("--- /dev/null\n"));
    out.push_str(&format!("+++ b/{}\n", relative_path));
    out.push_str(&format!("@@ -0,0 +1,{} @@\n", count));
    for line in &lines {
        out.push_str(&format!("+{}\n", line));
    }
    if !content.ends_with('\n') {
        out.push_str("\\ No newline at end of file\n");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_modified() {
        let input = " M src/main.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "src/main.rs");
        assert_eq!(entries[0].status, "modified");
    }

    #[test]
    fn test_parse_staged_modified() {
        let input = "M  src/main.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "src/main.rs");
        assert_eq!(entries[0].status, "modified");
    }

    #[test]
    fn test_parse_added() {
        let input = "A  src/new.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "src/new.rs");
        assert_eq!(entries[0].status, "added");
    }

    #[test]
    fn test_parse_deleted() {
        let input = " D src/old.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "src/old.rs");
        assert_eq!(entries[0].status, "deleted");
    }

    #[test]
    fn test_parse_untracked() {
        let input = "?? untracked.txt\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "untracked.txt");
        assert_eq!(entries[0].status, "untracked");
    }

    #[test]
    fn test_parse_renamed() {
        let input = "R  old.rs -> new.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "new.rs");
        assert_eq!(entries[0].status, "renamed");
        assert_eq!(entries[0].old_path, Some("old.rs".to_string()));
    }

    #[test]
    fn test_parse_empty() {
        let entries = parse_porcelain_v1("");
        assert!(entries.is_empty());
    }

    #[test]
    fn test_parse_multiple() {
        let input = " M modified.rs\nA  added.rs\n?? untracked.rs\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].status, "modified");
        assert_eq!(entries[1].status, "added");
        assert_eq!(entries[2].status, "untracked");
    }

    #[test]
    fn test_parse_filters_directories() {
        let input = "?? build/\n?? src/main.rs\n M Cargo.toml\n";
        let entries = parse_porcelain_v1(input);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].path, "src/main.rs");
        assert_eq!(entries[0].status, "untracked");
        assert_eq!(entries[1].path, "Cargo.toml");
        assert_eq!(entries[1].status, "modified");
    }

    #[test]
    fn test_format_untracked_diff() {
        let dir = std::env::temp_dir().join("openmate_test_untracked");
        std::fs::create_dir_all(&dir).unwrap();
        let file_path = dir.join("new_file.rs");
        std::fs::write(&file_path, "fn main() {\n    println!(\"hi\");\n}\n").unwrap();

        let result = format_untracked_diff("src/new_file.rs", &file_path);
        assert!(result.starts_with("diff --git a/src/new_file.rs b/src/new_file.rs\n"));
        assert!(result.contains("new file mode 100644\n"));
        assert!(result.contains("--- /dev/null\n"));
        assert!(result.contains("+++ b/src/new_file.rs\n"));
        assert!(result.contains("@@ -0,0 +1,3 @@\n"));
        assert!(result.contains("+fn main() {\n"));
        assert!(result.contains("+    println!(\"hi\");\n"));

        std::fs::remove_dir_all(&dir).ok();
    }
}
